# ProjectService Architecture Patterns & Best Practices

**Purpose:** Guide for developers working with the ProjectService layer and similar services

---

## Layered Architecture Pattern

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Layer                                │
│              ProjectController (REST API)                    │
│  - Request/Response mapping                                 │
│  - HTTP status codes                                        │
│  - Parameter extraction                                     │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                  Service Layer                               │
│             ProjectService (Business Logic)                  │
│  - Authorization enforcement                                │
│  - Input validation                                         │
│  - Transaction management                                   │
│  - Event emission                                           │
│  - Structured logging                                       │
└─────────────────────────┬───────────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        ↓                 ↓                 ↓
┌──────────────────┐ ┌──────────────┐ ┌──────────────────┐
│  Repository      │ │ Audit        │ │ Notification     │
│  Layer           │ │ Service      │ │ Service          │
└──────────────────┘ └──────────────┘ └──────────────────┘
        ↓
┌─────────────────────────────────────────────────────────────┐
│              Persistence Layer (JPA)                         │
│                  Database Schema                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Service Layer Responsibilities

### 1. Authorization
Enforce who can perform which operations:

```java
// Pattern: Authorization before data access
private void authoriseTeamMemberQuery(UUID teamMemberId, TenantContext tenant) {
  if (teamMemberId.equals(tenant.userId())) {
    return; // Allow self-queries
  }
  
  // Reject unauthorized access
  throw new ForbiddenException("Not authorised to view projects for user " + teamMemberId);
}

// Usage in public methods:
@Transactional(readOnly = true)
public List<ProjectResponse> getByTeam(UUID teamMemberId, TenantContext tenant) {
  authoriseTeamMemberQuery(teamMemberId, tenant); // Check first
  // ... rest of logic
}
```

### 2. Validation
Validate business rules beyond annotation constraints:

```java
// Pattern: Additional validation in service
private void validateCreateRequest(CreateProjectRequest request, TenantContext tenant) {
  String trimmedName = request.name().trim();
  if (trimmedName.isEmpty()) {
    throw new IllegalArgumentException("Project name cannot be whitespace-only after trim");
  }
  
  if (request.teamMemberIds().size() > 50) {
    throw new IllegalArgumentException("Maximum 50 team members per project");
  }
  
  // TODO: Verify team members exist and belong to organisation
  for (UUID memberId : request.teamMemberIds()) {
    if (!userBelongsToOrganisation(memberId, tenant.organisationId())) {
      throw new IllegalArgumentException("User " + memberId + " does not belong to organisation");
    }
  }
}
```

### 3. Idempotency
Prevent duplicate side effects from concurrent requests:

```java
// Pattern: Idempotency check
@Transactional
public ProjectResponse updateStatus(UUID id, UpdateProjectStatusRequest request, TenantContext tenant) {
  Project project = getAuthorisedProject(id, tenant);
  ProjectStatus oldStatus = project.getStatus();
  
  // If already in target state, return without changes
  if (oldStatus.equals(request.status())) {
    log.debug("Project already in status {}, returning without update", request.status());
    return toResponse(project, tenant);
  }
  
  // ... rest of update logic
}
```

### 4. Tenant Isolation
Verify all data belongs to the requesting tenant:

```java
// Pattern: Tenant boundary verification
private Project getAuthorisedProject(UUID projectId, TenantContext tenant) {
  Project project = repository.findByIdAndOrganisationIdAndDeletedFalse(
    projectId,
    tenant.organisationId()
  ).orElseThrow(() -> new NotFoundException("Project not found"));
  
  // Defensive verification
  if (!project.getOrganisationId().equals(tenant.organisationId())) {
    log.error("TENANT_BOUNDARY_VIOLATION projectId={} expectedOrg={} actualOrg={}",
      projectId, tenant.organisationId(), project.getOrganisationId());
    throw new IllegalStateException("Tenant boundary violation detected");
  }
  
  return project;
}
```

### 5. Event Emission
Emit audit and notification events for state changes:

```java
// Pattern: Event emission with state snapshots
@Transactional
public void delete(UUID id, TenantContext tenant) {
  Project project = getAuthorisedProject(id, tenant);
  String previousState = serializeProject(project);
  
  project.softDelete();
  repository.save(project);
  
  String finalState = serializeProject(project);
  emitProjectEvent(project, previousState, EventType.MILESTONE_DELETED, tenant, finalState);
}

// Safe event description (no user input)
private String formatEventDescription(EventType eventType) {
  return switch (eventType) {
    case MILESTONE_CREATED -> "Project was created";
    case MILESTONE_CLOSED -> "Project was closed";
    case MILESTONE_DELETED -> "Project was deleted";
    default -> "Project event: " + eventType;
  };
}
```

### 6. Structured Logging
Use MDC for tenant context and structured log statements:

```java
// Pattern: Structured logging with context
private void setTenantLogContext(TenantContext tenant) {
  MDC.put(LOG_CONTEXT_ORG, tenant.organisationId().toString());
  MDC.put(LOG_CONTEXT_USER, tenant.userId().toString());
}

// In public method:
@Transactional
public ProjectResponse create(CreateProjectRequest request, TenantContext tenant) {
  setTenantLogContext(tenant); // Set context first
  log.info("Creating project name={}", request.name());
  
  try {
    // ... business logic
    log.info("Project created projectId={} teamSize={}", project.getId(), project.getTeamMemberIds().size());
  } catch (Exception e) {
    log.error("Failed to create project name={}", request.name(), e);
    throw e;
  }
}
```

---

## Soft Delete Pattern

### Model Level
```java
@Entity
public class Project {
  @Column(nullable = false)
  private Boolean deleted = false;
  
  @Column(name = "deleted_at")
  private Instant deletedAt;
  
  public void softDelete() {
    this.deleted = true;
    this.deletedAt = Instant.now();
  }
  
  public boolean isDeleted() {
    return deleted != null && deleted;
  }
}
```

### Repository Level
```java
public interface ProjectRepository extends JpaRepository<Project, UUID> {
  // All queries include soft-delete filter
  Optional<Project> findByIdAndOrganisationIdAndDeletedFalse(UUID id, UUID orgId);
  List<Project> findByOrganisationIdAndDeletedFalse(UUID organisationId);
}
```

### Service Level
```java
@Transactional
public void delete(UUID id, TenantContext tenant) {
  Project project = getAuthorisedProject(id, tenant); // Fetches only non-deleted
  project.softDelete();
  repository.save(project);
  // Data retained for compliance and recovery
}
```

### Benefits
- ✅ Data recovery without database restore
- ✅ Complete audit trail (when something was deleted)
- ✅ Compliance with retention policies
- ✅ Foreign key references still valid
- ✅ Can restore accidentally deleted records

---

## Error Handling Patterns

### Validation Errors (400 Bad Request)
```java
// RequestParam validation failed
try {
  validateCreateRequest(request, tenant);
} catch (IllegalArgumentException e) {
  log.warn("Validation error: {}", e.getMessage());
  throw e; // Spring GlobalExceptionHandler converts to 400
}
```

### Authorization Errors (403 Forbidden)
```java
// User not authorized for operation
if (!authorised(tenant, resource)) {
  log.warn("Unauthorized access attempt user={} resource={}", tenant.userId(), resource.getId());
  throw new ForbiddenException("Not authorized to access this resource");
}
```

### Not Found Errors (404 Not Found)
```java
// Resource doesn't exist or is deleted
Project project = repository.findByIdAndOrganisationIdAndDeletedFalse(id, orgId)
  .orElseThrow(() -> {
    log.warn("Resource not found id={} org={}", id, orgId);
    return new NotFoundException("Project not found");
  });
```

### Internal Errors (500 Server Error)
```java
// Unexpected error that cannot be recovered
try {
  return objectMapper.writeValueAsString(project);
} catch (JsonProcessingException e) {
  log.error("Serialization failed projectId={}", project.getId(), e);
  throw new IllegalStateException("Could not serialize project snapshot", e);
}
```

---

## Transaction Management Patterns

### Read Operations
```java
@Transactional(readOnly = true)
public ProjectResponse get(UUID id, TenantContext tenant) {
  // No persistence context flush needed
  // Database can apply read-only optimizations
  return toResponse(getAuthorisedProject(id, tenant), tenant);
}
```

### Write Operations
```java
@Transactional
public ProjectResponse create(CreateProjectRequest request, TenantContext tenant) {
  // Full transaction support
  // Rollback on exception
  // Flush on commit
  Project p = repository.save(new Project()); // Immediately persisted
  emitProjectEvent(p, null, EventType.MILESTONE_CREATED, tenant);
  return toResponse(p, tenant);
}
```

### Rollback Behavior
```java
@Transactional
public ProjectResponse update(UUID id, UpdateProjectStatusRequest request, TenantContext tenant) {
  Project p = getAuthorisedProject(id, tenant); // Throws NotFoundException
  // If exception: entire transaction rolled back
  // Project not updated, audit not recorded, notifications not sent
  
  p.setStatus(request.status());
  repository.save(p);
  
  emitProjectEvent(p, previousState, EventType.MILESTONE_UPDATED, tenant);
  // If emitProjectEvent throws: entire transaction rolled back
}
```

---

## Data Transformation Patterns

### Secure Response Mapping
```java
private ProjectResponse toResponse(Project project, TenantContext tenant) {
  // Tenant boundary check
  if (tenant != null && !project.getOrganisationId().equals(tenant.organisationId())) {
    throw new IllegalStateException("Tenant boundary violation");
  }
  
  // Exclude sensitive fields (organisationId, deleted info)
  return new ProjectResponse(
    project.getId(),
    project.getName(),           // Safe: user-provided, validated
    project.getStatus(),         // Safe: enum
    Set.copyOf(project.getTeamMemberIds()), // Safe: UUIDs only
    project.getCreatedAt(),      // Safe: timestamp
    project.getUpdatedAt()       // Safe: timestamp
    // organisationId NOT exposed
    // deleted, deletedAt NOT exposed
  );
}
```

### Safe Serialization
```java
private String serializeProject(Project project) {
  try {
    // Convert to DTO first (excludes sensitive fields)
    ProjectResponse dto = toResponse(project, null);
    return objectMapper.writeValueAsString(dto);
  } catch (JsonProcessingException e) {
    log.error("Serialization failed", e);
    throw new IllegalStateException("Could not serialize project snapshot", e);
  }
}
```

---

## Testing Patterns

### Unit Testing Service Authorization
```java
@Test
void testGetByTeamRejectsUnauthorizedUsers() {
  // User A tries to query projects for User B
  UUID userB = UUID.randomUUID();
  TenantContext userA = new TenantContext(
    UUID.randomUUID(), // User A ID
    organisationId,
    "127.0.0.1"
  );
  
  assertThrows(ForbiddenException.class, () -> {
    service.getByTeam(userB, userA);
  });
}

@Test
void testGetByTeamAllowsSelfQuery() {
  UUID userId = UUID.randomUUID();
  TenantContext tenant = new TenantContext(userId, organisationId, "127.0.0.1");
  
  // Should not throw
  List<ProjectResponse> projects = service.getByTeam(userId, tenant);
  // Assertions...
}
```

### Unit Testing Idempotency
```java
@Test
void testUpdateStatusIsIdempotent() {
  Project project = createTestProject(ProjectStatus.CLOSED);
  UpdateProjectStatusRequest request = new UpdateProjectStatusRequest(ProjectStatus.CLOSED);
  
  ProjectResponse response = service.updateStatus(project.getId(), request, tenant);
  
  // Should not emit event (verify auditService was not called)
  verify(auditService, never()).record(any(), any());
  verify(notificationService, never()).dispatch(any(), any(), any(), any(), any());
}
```

---

## Common Mistakes to Avoid

### ❌ Don't Skip Authorization Checks
```java
// WRONG: Allows users to query any team member's projects
@Transactional(readOnly = true)
public List<ProjectResponse> getByTeam(UUID teamMemberId, TenantContext tenant) {
  return repository.findByOrganisationIdAndTeamMemberIdsContainingAndDeletedFalse(
    tenant.organisationId(), teamMemberId
  ).stream().map(this::toResponse).toList();
}

// RIGHT: Include authorization check
@Transactional(readOnly = true)
public List<ProjectResponse> getByTeam(UUID teamMemberId, TenantContext tenant) {
  authoriseTeamMemberQuery(teamMemberId, tenant); // ✅ Check first
  return repository.findByOrganisationIdAndTeamMemberIdsContainingAndDeletedFalse(
    tenant.organisationId(), teamMemberId
  ).stream().map(this::toResponse).toList();
}
```

### ❌ Don't Include User Input in Audit Messages
```java
// WRONG: SQL injection risk
String message = "Project '" + project.getName() + "' was created";

// RIGHT: Use safe enum-based descriptions
String message = formatEventDescription(EventType.MILESTONE_CREATED);
```

### ❌ Don't Hard Delete
```java
// WRONG: Data permanently lost
repository.delete(project);

// RIGHT: Soft delete with audit trail
project.softDelete();
repository.save(project);
```

### ❌ Don't Expose Sensitive Fields
```java
// WRONG: Exposes internal structure
new ProjectResponse(
  project.getId(),
  project.getOrganisationId(), // ❌ Tenant enumeration risk
  project.getName(),
  ...
)

// RIGHT: Exclude organisationId
new ProjectResponse(
  project.getId(),
  project.getName(),
  ...
  // organisationId not exposed
)
```

### ❌ Don't Forget Tenant Context in Logging
```java
// WRONG: Can't trace which tenant
log.info("Project created projectId={}", project.getId());

// RIGHT: Include tenant context
MDC.put("organisationId", tenant.organisationId().toString());
log.info("Project created projectId={}", project.getId());
```

---

## Migration Checklist

When adding a new service following this pattern:

- [ ] Create Entity with soft delete support (`deleted`, `deletedAt`)
- [ ] Create Repository with soft delete queries
- [ ] Create DTOs with comprehensive validation
- [ ] Create Service with these methods:
  - [ ] Authorization check method
  - [ ] Validation method
  - [ ] Data access method with tenant verification
  - [ ] Event emission method
  - [ ] Response transformation method
  - [ ] Logging context method
- [ ] Create Controller with comprehensive JavaDoc
- [ ] Add structured logging with MDC
- [ ] Add error handling for all exception types
- [ ] Add unit tests for authorization, validation, idempotency
- [ ] Add integration tests for full workflows
- [ ] Update database migration script
- [ ] Add REFACTORING_SUMMARY.md documenting changes

---

## Related Documentation

- [SECURITY_REVIEW.md](./SECURITY_REVIEW.md) — Security vulnerabilities and fixes
- [REFACTORING_SUMMARY.md](./REFACTORING_SUMMARY.md) — Detailed refactoring changes
- [Spring Data JPA Docs](https://spring.io/projects/spring-data-jpa)
- [Spring Security Best Practices](https://spring.io/projects/spring-security)

---

**End of Architecture Guide**
