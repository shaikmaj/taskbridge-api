# Security Review: ProjectService & Related Components
**Date:** 2026-08-30  
**Reviewer Role:** Senior Security Engineer  
**Severity Levels:** 🔴 Critical | 🟠 High | 🟡 Medium | 🔵 Low

---

## Executive Summary
The ProjectService implements multi-tenant project management with reasonable foundational security patterns but contains **4 critical vulnerabilities**, **3 high-severity issues**, and several architectural gaps that require immediate attention before production deployment.

---

## 🔴 CRITICAL FINDINGS

### 1. **X-Forwarded-For Header Spoofing (Trust Boundary Violation)**
**Location:** [TenantContextResolver.java](src/main/java/com/taskbridge/common/TenantContextResolver.java#L16)

```java
private String clientIp(HttpServletRequest r){
  String forwarded=r.getHeader("X-Forwarded-For");
  return forwarded==null||forwarded.isBlank()?r.getRemoteAddr():forwarded.split(",")[0].trim();
}
```

**Issue:** The code blindly trusts `X-Forwarded-For` header without validating:
- If the request actually came through a proxy
- If the header was injected by an untrusted intermediate
- Whether the header contains valid IP format

**Attack Scenario:**
```
Attacker sends: X-Forwarded-For: 192.168.1.1
Application logs: 192.168.1.1 (false trail)
Audit trail now points to innocent IP
```

**Risk:** Audit trails and security logs are unreliable, defeating forensics.

**Recommendation:**
```java
private String clientIp(HttpServletRequest r){
  // Only trust X-Forwarded-For if behind known load balancer
  String forwarded = r.getHeader("X-Forwarded-For");
  if(forwarded != null && !forwarded.isBlank()) {
    String ip = forwarded.split(",")[0].trim();
    if(isValidIp(ip)) return ip; // Add IP validation
  }
  return r.getRemoteAddr(); // Fallback to servlet container IP
  // TODO: Configure proxy trust chain in Spring Cloud Gateway or AWS ALB
}

private boolean isValidIp(String ip) {
  // Validate IPv4/IPv6 format
  return ip.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$") || 
         ip.matches("^(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$");
}
```

---

### 2. **Missing Authorization Check in getByTeam()**
**Location:** [ProjectService.java](src/main/java/com/taskbridge/projects/service/ProjectService.java#L36)

```java
@Transactional(readOnly=true) 
public List<ProjectResponse> getByTeam(UUID memberId, TenantContext tenant){ 
  return repository.findAllByOrganisationIdAndTeamMemberIdsContaining(
    tenant.organisationId(), memberId
  ).stream().map(this::toResponse).toList(); 
}
```

**Issue:** No verification that the caller (`tenant.userId()`) is authorized to query projects for `memberId`.

**Attack Scenario:**
```
Attacker (User A) calls:
GET /projects?teamMemberId=<User B UUID>

Application returns all projects User B belongs to, even if User A 
has no relationship with User B or those projects.

User A now has full visibility into another user's project portfolio.
```

**Risk:** Unauthorized data access, privacy breach, information disclosure.

**Recommendation:**
```java
@Transactional(readOnly=true) 
public List<ProjectResponse> getByTeam(UUID memberId, TenantContext tenant){ 
  // CRITICAL: Verify caller is authorized to view this team member's projects
  if(!memberId.equals(tenant.userId()) && !isManagerOrAdmin(tenant)) {
    throw new ForbiddenException(
      "Not authorized to view projects for user " + memberId
    );
  }
  return repository.findAllByOrganisationIdAndTeamMemberIdsContaining(
    tenant.organisationId(), memberId
  ).stream().map(this::toResponse).toList(); 
}

private boolean isManagerOrAdmin(TenantContext tenant) {
  // TODO: Query user roles/permissions table
  // For now, this is a placeholder
  return false; 
}
```

**Database Query (Recommended):**
```sql
-- Add to repository
List<Project> findByIdInAndOrganisationIdAndTeamMemberIdsContaining(
  Set<UUID> projectIds, UUID organisationId, UUID teamMemberId
);
```

---

### 3. **Tenant Isolation Bypass via Direct Model Exposure**
**Location:** [ProjectService.java](src/main/java/com/taskbridge/projects/service/ProjectService.java) - Multiple methods

**Issue:** The `toResponse()` method exposes all fields without filtering:

```java
private ProjectResponse toResponse(Project p){ 
  return new ProjectResponse(p.getId(), p.getOrganisationId(), p.getName(), 
    p.getStatus(), Set.copyOf(p.getTeamMemberIds()), 
    p.getCreatedAt(), p.getUpdatedAt()); 
}
```

**The Problem:**
1. `organisationId` is exposed in responses—a user could enumerate organisations by trying different IDs
2. Entire `teamMemberIds` Set is returned—reveals sensitive team structure
3. No row-level security (RLS) at the database level

**Attack Scenario:**
```
User A calls: GET /projects/abc123
Returns: {id: abc123, organisationId: def456, teamMemberIds: [user1, user2, user3]}

User A now knows:
- The organisation structure
- All users in this organisation
- Team membership details
- Can use this to orchestrate further attacks
```

**Risk:** Tenant boundary bypass through data inference.

**Recommendation:**
```java
private ProjectResponse toResponse(Project p, TenantContext tenant) { 
  // Only expose organisationId if it matches the tenant (redundant, but explicit)
  if(!p.getOrganisationId().equals(tenant.organisationId())) {
    throw new IllegalStateException("Tenant boundary violation");
  }
  
  // Filter team member IDs—only expose if caller is admin or team member
  Set<UUID> visibleMembers = new HashSet<>();
  if(isTenantAdmin(tenant)) {
    visibleMembers = Set.copyOf(p.getTeamMemberIds());
  } else if(p.getTeamMemberIds().contains(tenant.userId())) {
    visibleMembers = Set.copyOf(p.getTeamMemberIds());
  }
  // else: return empty set or null
  
  return new ProjectResponse(p.getId(), null, // Don't expose organisationId
    p.getName(), p.getStatus(), visibleMembers, 
    p.getCreatedAt(), p.getUpdatedAt()); 
}

// Update all calls to pass tenant:
public ProjectResponse get(UUID id, TenantContext tenant){
  return toResponse(getEntity(id, tenant.organisationId()), tenant);
}
```

---

### 4. **SQL Injection Risk in Audit/Notification Payloads**
**Location:** [ProjectService.java](src/main/java/com/taskbridge/projects/service/ProjectService.java#L50-52)

```java
private void emit(Project p, String previous, EventType type, TenantContext tenant, String next){
  auditService.record(new CreateAuditRequest(
    type, "PROJECT_MILESTONE", p.getId(), p.getId(), previous, next
  ), tenant);
  notificationService.dispatch(p.getTeamMemberIds(), p.getId(), type,
    "Project '" + p.getName() + "' changed: " + type, // String concatenation!
    p.getOrganisationId()
  );
}
```

**Issue:** String concatenation with `p.getName()` in notification messages. If `NotificationService` or audit system doesn't properly parameterize queries, this becomes a SQL injection vector.

**Attack Scenario:**
```
Attacker creates project with name:
"'; DROP TABLE projects; --"

Notification message built:
"Project '; DROP TABLE projects; --' changed: MILESTONE_CREATED"

If NotificationService uses string interpolation in SQL (not parameterized):
INSERT INTO notifications(message, ...) VALUES('Project '; DROP TABLE projects; --' ...'
^ SQL Injection
```

**Risk:** Database manipulation, data loss, unauthorized modification.

**Recommendation:**
```java
private void emit(Project p, String previous, EventType type, TenantContext tenant, String next){
  auditService.record(new CreateAuditRequest(
    type, "PROJECT_MILESTONE", p.getId(), p.getId(), previous, next
  ), tenant);
  
  // Use parameterized message template
  notificationService.dispatch(p.getTeamMemberIds(), p.getId(), type,
    EventType.MILESTONE_CREATED == type ? 
      "Project created" : "Project status changed",
    p.getOrganisationId()
  );
  // Don't include user-provided data (p.getName()) in structured messages
}
```

**Or if name is needed:**
```java
notificationService.dispatch(
  p.getTeamMemberIds(), p.getId(), type,
  Map.of("projectName", p.getName(), "eventType", type.toString()),
  p.getOrganisationId()
);
```

---

## 🟠 HIGH SEVERITY FINDINGS

### 5. **Missing Idempotency Checks (Race Condition in Status Updates)**
**Location:** [ProjectService.java](src/main/java/com/taskbridge/projects/service/ProjectService.java#L31-35)

```java
@Transactional 
public ProjectResponse updateStatus(UUID id, UpdateProjectStatusRequest request, TenantContext tenant){
  Project p = getEntity(id, tenant.organisationId()); 
  ProjectStatus old = p.getStatus(); 
  p.setStatus(request.status());
  // NO idempotency check or duplicate prevention
  ...
}
```

**Issue:** Two concurrent requests to close the same project generate two separate audit events.

**Attack Scenario:**
```
Request 1 & 2 (nearly simultaneous):
Both call updateStatus(projectId, CLOSED)

Timeline:
1. Both load Project (status: OPEN)
2. Both update to CLOSED
3. Both emit MILESTONE_CLOSED event
4. Audit shows 2 close events (invalid state machine)
5. Notifications sent twice (spam)
```

**Risk:** Audit trail corruption, incorrect event sequencing, business logic errors.

**Recommendation:**
```java
@Transactional 
public ProjectResponse updateStatus(UUID id, UpdateProjectStatusRequest request, TenantContext tenant){
  Project p = getEntity(id, tenant.organisationId()); 
  
  // Idempotency: Check if already in target state
  if(p.getStatus().equals(request.status())) {
    log.warn("Project already in status {}, ignoring duplicate request", request.status());
    return toResponse(p, tenant);
  }
  
  String previous = json(p); 
  ProjectStatus old = p.getStatus(); 
  p.setStatus(request.status());
  p = repository.save(p);
  
  EventType type = mapStatusToEventType(old, request.status());
  emit(p, previous, type, tenant); 
  return toResponse(p, tenant);
}

private EventType mapStatusToEventType(ProjectStatus old, ProjectStatus newStatus) {
  if(newStatus == ProjectStatus.CLOSED) return EventType.MILESTONE_CLOSED;
  if(old == ProjectStatus.CLOSED) return EventType.MILESTONE_REOPENED;
  return EventType.MILESTONE_UPDATED;
}
```

---

### 6. **No Soft Delete / Audit Trail After Deletion**
**Location:** [ProjectService.java](src/main/java/com/taskbridge/projects/service/ProjectService.java#L45-49)

```java
@Transactional 
public void delete(UUID id, TenantContext tenant){ 
  Project p = getEntity(id, tenant.organisationId()); 
  String previous = json(p); 
  emit(p, previous, EventType.MILESTONE_DELETED, tenant, "{}"); 
  repository.delete(p);  // Hard delete - data is permanently lost
}
```

**Issue:** Hard delete removes all project history. Once deleted:
- No recovery mechanism
- Audit trail becomes incomplete (final state is empty `{}`)
- Legal/compliance requirements may mandate retention
- Orphaned references in child tables cause data integrity issues

**Risk:** Data loss, compliance violations, incomplete audit trail.

**Recommendation:**
```java
public class Project {
  @Column(nullable=false)
  private Boolean deleted = false;
  
  @Column(name="deleted_at")
  private Instant deletedAt;
  
  public void softDelete() {
    this.deleted = true;
    this.deletedAt = Instant.now();
  }
}

// Update repository query to exclude soft-deleted records
public interface ProjectRepository extends JpaRepository<Project, UUID> {
  Optional<Project> findByIdAndOrganisationIdAndDeletedFalse(
    UUID id, UUID organisationId
  );
  
  List<Project> findByOrganisationIdAndTeamMemberIdsContainingAndDeletedFalse(
    UUID organisationId, UUID teamMemberId
  );
}

// Update service
@Transactional 
public void delete(UUID id, TenantContext tenant){ 
  Project p = getEntity(id, tenant.organisationId()); 
  String previous = json(p); 
  p.softDelete();
  repository.save(p);
  emit(p, previous, EventType.MILESTONE_DELETED, tenant, json(p)); 
  log.info("Project soft-deleted projectId={} deletedAt={}", id, p.getDeletedAt());
}

private Project getEntity(UUID id, UUID org){ 
  return repository.findByIdAndOrganisationIdAndDeletedFalse(id, org)
    .orElseThrow(()->new NotFoundException("Project not found")); 
}
```

---

### 7. **Insufficient Input Validation on Team Member IDs**
**Location:** [ProjectService.java](src/main/java/com/taskbridge/projects/service/ProjectService.java#L25) and [CreateProjectRequest.java](src/main/java/com/taskbridge/projects/dto/CreateProjectRequest.java)

```java
public record CreateProjectRequest(
  @NotBlank @Size(max=120) String name, 
  @NotEmpty Set<@NotNull UUID> teamMemberIds  // Only checks not empty, not null
) {}

@Transactional 
public ProjectResponse create(CreateProjectRequest request, TenantContext tenant){
  Project p = new Project(); 
  p.setOrganisationId(tenant.organisationId());
  p.setName(request.name().trim());  // Only trims, no further validation
  p.setStatus(ProjectStatus.OPEN);
  p.setTeamMemberIds(new HashSet<>(request.teamMemberIds()));  // No member existence check
  ...
}
```

**Issues:**
1. Team member IDs are not validated to exist or belong to the organisation
2. No validation that caller is authorized to add specific users
3. No check for empty string after trim (e.g., `"   "` becomes `""`)
4. Max size of 120 chars is very permissive for SQL injection attempts

**Attack Scenarios:**
```
1. Add non-existent user UUID to project:
   POST /projects {teamMemberIds: [00000000-0000-0000-0000-000000000000]}
   
2. Whitespace-only name:
   POST /projects {name: "     "}
   
3. SQL-like characters (with inadequate downstream escaping):
   POST /projects {name: "'; DROP --"}
```

**Risk:** Referential integrity violations, data corruption, injection attacks.

**Recommendation:**
```java
public record CreateProjectRequest(
  @NotBlank @Size(min=3, max=120) 
  @Pattern(regexp = "^[a-zA-Z0-9\\s\\-_.()]+$", 
    message = "Project name contains invalid characters")
  String name, 
  
  @NotEmpty @Size(max=50, message="Max 50 team members per project")
  Set<@NotNull UUID> teamMemberIds
) {}

@Transactional 
public ProjectResponse create(CreateProjectRequest request, TenantContext tenant){
  Project p = new Project(); 
  p.setOrganisationId(tenant.organisationId());
  
  // Validate name after trim
  String trimmedName = request.name().trim();
  if(trimmedName.isEmpty()) {
    throw new IllegalArgumentException("Project name cannot be whitespace-only");
  }
  p.setName(trimmedName);
  p.setStatus(ProjectStatus.OPEN);
  
  // Validate team members exist and belong to organisation
  Set<UUID> validMembers = validateTeamMembers(request.teamMemberIds(), tenant);
  p.setTeamMemberIds(validMembers);
  
  p = repository.save(p); 
  emit(p, null, EventType.MILESTONE_CREATED, tenant); 
  return toResponse(p, tenant);
}

private Set<UUID> validateTeamMembers(Set<UUID> memberIds, TenantContext tenant) {
  // TODO: Query user repository to verify:
  // 1. All UUIDs are valid
  // 2. All users belong to the organisation
  // 3. Caller is authorized to add these users
  
  if(memberIds.size() > 50) {
    throw new IllegalArgumentException("Max 50 team members per project");
  }
  
  Set<UUID> validMembers = new HashSet<>();
  for(UUID memberId : memberIds) {
    if(!userBelongsToOrganisation(memberId, tenant.organisationId())) {
      throw new IllegalArgumentException(
        "User " + memberId + " does not belong to organisation"
      );
    }
    validMembers.add(memberId);
  }
  return validMembers;
}

private boolean userBelongsToOrganisation(UUID userId, UUID orgId) {
  // Query user service/repository
  return false; // Placeholder
}
```

---

## 🟡 MEDIUM SEVERITY FINDINGS

### 8. **Overly Permissive Error Messaging**
**Location:** [GlobalExceptionHandler.java](src/main/java/com/taskbridge/common/GlobalExceptionHandler.java#L17)

```java
@ExceptionHandler(MethodArgumentNotValidException.class) 
ResponseEntity<ApiError> invalid(MethodArgumentNotValidException ex){
  var details = ex.getBindingResult().getFieldErrors()
    .stream()
    .map(e -> e.getField() + ": " + e.getDefaultMessage())
    .toList();
  return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", 
    "Request validation failed", details);
}
```

**Issue:** While `server.error.include-message: never` is set, the validation details still leak field names and constraints.

**Attack Scenario:**
```
Attacker submits invalid request, learns:
- "teamMemberIds: must not be empty" → Knows field name and validation logic
- "name: size must be between 1 and 120" → Knows field size constraints
- Can use this to reverse-engineer API contracts
```

**Risk:** API information disclosure (low impact, but compounds with other issues).

**Recommendation:**
```java
@ExceptionHandler(MethodArgumentNotValidException.class) 
ResponseEntity<ApiError> invalid(MethodArgumentNotValidException ex){
  log.warn("Validation error from IP: {} details: {}", 
    getClientIp(), getValidationDetails(ex));
  
  // Don't expose field names or constraints in response
  return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", 
    "Request validation failed", List.of());
    // Return empty details list
}

private List<String> getValidationDetails(MethodArgumentNotValidException ex) {
  return ex.getBindingResult().getFieldErrors()
    .stream()
    .map(e -> e.getField() + ": " + e.getDefaultMessage())
    .toList();
}
```

---

### 9. **Missing Rate Limiting and DDoS Protection**
**Location:** [ProjectController.java](src/main/java/com/taskbridge/projects/controller/ProjectController.java) - All endpoints

**Issue:** No rate limiting, request throttling, or DDoS protection on any endpoint.

**Attack Scenario:**
```
for(int i = 0; i < 10000; i++) {
  http.get("/projects?teamMemberId=<uuid>");  // Enumeration + resource exhaustion
}
Result: Database overwhelmed, service down
```

**Risk:** Denial of service, resource exhaustion.

**Recommendation:**
```xml
<!-- Add to pom.xml -->
<dependency>
  <groupId>io.github.bucket4j</groupId>
  <artifactId>bucket4j-core</artifactId>
  <version>7.10.0</version>
</dependency>
```

```java
@Component
public class RateLimitingFilter implements Filter {
  private final Bucket bucket = Bucket4j.builder()
    .addLimit(Limit.of(1000, Bandwidth.classic(1000, Refill.intervally(1000, Duration.ofMinutes(1)))))
    .build();
  
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, 
    FilterChain chain) throws IOException, ServletException {
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    
    if(probe.isConsumed()) {
      chain.doFilter(request, response);
    } else {
      HttpServletResponse httpResponse = (HttpServletResponse) response;
      httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      httpResponse.getWriter().write("Rate limit exceeded");
    }
  }
}
```

---

### 10. **No Request Logging / Audit Trail for Actions**
**Location:** [ProjectService.java](src/main/java/com/taskbridge/projects/service/ProjectService.java) - All methods

**Issue:** While audit events are emitted, there's no structured logging of:
- Who called the endpoint
- What parameters were used
- When it was called
- The outcome (success/failure)

**Recommendation:**
```java
@Aspect
@Component
public class AuditAspect {
  private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
  
  @Around("execution(* com.taskbridge.projects.service.ProjectService.*(..))")
  public Object auditProjectOperations(ProceedingJoinPoint pjp) throws Throwable {
    MethodSignature signature = (MethodSignature) pjp.getSignature();
    String methodName = signature.getName();
    TenantContext tenant = extractTenantContext(pjp);
    Instant start = Instant.now();
    
    try {
      Object result = pjp.proceed();
      auditLog.info("ACTION=SUCCESS method={} user={} org={} duration={}", 
        methodName, tenant.userId(), tenant.organisationId(), 
        Duration.between(start, Instant.now()).toMillis());
      return result;
    } catch(Exception e) {
      auditLog.warn("ACTION=FAILED method={} user={} org={} error={}", 
        methodName, tenant.userId(), tenant.organisationId(), e.getMessage());
      throw e;
    }
  }
}
```

---

## 🔵 LOW SEVERITY FINDINGS

### 11. **No Authentication Verification**
While the code assumes `TenantContextResolver` provides authenticated context, there's no explicit check that:
- The request is authenticated (e.g., valid JWT, session token)
- The tenant ID in headers matches the authenticated user's organisation

**Recommendation:** Add authentication filter/interceptor that validates JWT/session tokens before `TenantContextResolver` runs.

### 12. **Insufficient Logging of Security-Relevant Events**
- Project deletion not logged to persistent audit log (only in-memory events)
- Team member changes not audited
- Authorization failures not logged

**Recommendation:** Log all authorization failures, team membership changes, and deletions to a tamper-proof audit log.

### 13. **No CORS Configuration (Explicit)**
No CORS headers configured. Ensure Spring Security is properly configured to prevent:
```java
@Configuration
public class CorsConfig {
  @Bean
  public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/projects/**")
          .allowedOrigins("https://trusted-domain.com")
          .allowedMethods("GET", "POST", "PATCH", "DELETE")
          .allowedHeaders("X-User-Id", "X-Organisation-Id")
          .allowCredentials(true)
          .maxAge(3600);
      }
    };
  }
}
```

---

## Summary of Findings

| Issue | Severity | Impact | Fix Effort |
|-------|----------|--------|-----------|
| X-Forwarded-For spoofing | 🔴 Critical | Audit trail unreliability | Medium |
| Missing authz in getByTeam() | 🔴 Critical | Unauthorized data access | Low |
| Tenant isolation bypass | 🔴 Critical | Data leakage | Medium |
| SQL injection in notifications | 🔴 Critical | Database compromise | Low |
| Race condition in updates | 🟠 High | Audit corruption | Low |
| Hard delete without retention | 🟠 High | Data loss, compliance risk | Medium |
| Insufficient input validation | 🟠 High | Injection, referential integrity | Medium |
| Permissive error messages | 🟡 Medium | Info disclosure | Low |
| No rate limiting | 🟡 Medium | DoS risk | Medium |
| Missing audit logging | 🟡 Medium | Forensics gap | Medium |
| No auth verification | 🔵 Low | Depends on caller validation | High |
| Insufficient event logging | 🔵 Low | Forensics gap | Low |
| No CORS config | 🔵 Low | CSRF/cross-site risk | Low |

---

## Immediate Actions (Before Production)

1. ✅ **Add authorization check** to `getByTeam()` → 30 minutes
2. ✅ **Validate team member existence** in `create()` → 1 hour
3. ✅ **Remove unsafe string concatenation** in audit messages → 15 minutes
4. ✅ **Implement idempotency check** in `updateStatus()` → 30 minutes
5. ✅ **Replace hard delete with soft delete** → 2 hours
6. ✅ **Fix X-Forwarded-For trust chain** → 1 hour

**Estimated Total:** 5 hours of development + 2 hours testing = 1 day

---

## Future Enhancements (Post-MVP)

- Implement role-based access control (RBAC)
- Add database-level row-level security (RLS)
- Deploy WAF (Web Application Firewall)
- Implement advanced rate limiting and anomaly detection
- Add encrypted audit trail with immutable storage
- Implement comprehensive API security scanning

---

**End of Security Review**
