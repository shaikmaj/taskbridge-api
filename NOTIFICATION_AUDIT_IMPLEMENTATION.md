# Notification & Audit Service Implementation Guide

**Purpose:** Step-by-step implementation guide and validation checklist

---

## Pre-Implementation Checklist

### Architecture Review
- [ ] Design document reviewed by 2+ architects
- [ ] Security review completed (penetration testing plan)
- [ ] Performance requirements validated
- [ ] Compliance requirements documented (SOC2, GDPR, HIPAA)
- [ ] Disaster recovery procedures drafted

### Infrastructure Requirements
- [ ] PostgreSQL 14+ cluster with replication
- [ ] Redis cluster for caching (optional Phase 1)
- [ ] Message queue infrastructure (optional Phase 1)
- [ ] Elasticsearch cluster (Phase 2)
- [ ] S3/GCS backup storage
- [ ] Monitoring infrastructure (DataDog, New Relic, etc.)

### Team Preparation
- [ ] Team trained on multi-tenant patterns
- [ ] Security team briefed on audit strategy
- [ ] Ops team prepared for monitoring setup
- [ ] DBA prepared for schema management

---

## Implementation Phases

### Phase 1: Core Implementation (Weeks 1-2)

#### 1.1 Database Setup

**Step 1: Create audit_entries table**
```sql
CREATE TABLE audit_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type VARCHAR(40) NOT NULL,
  entity_type VARCHAR(60) NOT NULL,
  entity_id UUID NOT NULL,
  project_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  organisation_id UUID NOT NULL,
  actor_ip_address VARCHAR(45) NOT NULL,
  previous_state TEXT,
  new_state TEXT NOT NULL,
  timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  INDEX idx_audit_project_time (project_id, timestamp),
  INDEX idx_audit_org (organisation_id),
  INDEX idx_audit_entity (entity_type, entity_id),
  INDEX idx_audit_actor (actor_user_id, timestamp),
  INDEX idx_audit_timestamp (timestamp DESC)
) ENGINE=InnoDB
PARTITION BY RANGE (YEAR(timestamp)) (
  PARTITION p2024 VALUES LESS THAN (2025),
  PARTITION p2025 VALUES LESS THAN (2026),
  PARTITION p2026 VALUES LESS THAN (2027)
);
```

**Step 2: Create notifications table**
```sql
CREATE TABLE notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_user_id UUID NOT NULL,
  organisation_id UUID NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  project_id UUID NOT NULL,
  title VARCHAR(120) NOT NULL,
  message VARCHAR(500) NOT NULL,
  category VARCHAR(20) NOT NULL,
  priority INT NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  read_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  deleted_at TIMESTAMP WITH TIME ZONE,
  
  INDEX idx_notification_recipient (organisation_id, recipient_user_id, is_read, created_at DESC),
  INDEX idx_notification_expiry (organisation_id, expires_at),
  INDEX idx_notification_project (project_id, created_at DESC),
  
  FOREIGN KEY (organisation_id) REFERENCES organisations(id),
  FOREIGN KEY (recipient_user_id) REFERENCES users(id)
) ENGINE=InnoDB;
```

**Validation:**
- [ ] Both tables created successfully
- [ ] Indexes created and validated
- [ ] Partitioning verified
- [ ] Foreign keys set correctly

#### 1.2 Model Implementation

**Step 1: Enhance AuditEntry model**
```java
@Entity
@Immutable
@Table(name = "audit_entries", indexes = {
  @Index(name = "idx_audit_project_time", columnList = "project_id,timestamp"),
  @Index(name = "idx_audit_org", columnList = "organisation_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;
  
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private EventType eventType;
  
  @Column(nullable = false, length = 60)
  private String entityType;
  
  @Column(nullable = false)
  private UUID entityId;
  
  @Column(nullable = false, name = "project_id")
  private UUID projectId;
  
  @Column(nullable = false)
  private UUID actorUserId;
  
  @Column(nullable = false, name = "organisation_id")
  private UUID organisationId;
  
  @Column(nullable = false, length = 45)
  private String actorIpAddress;
  
  @Lob
  @Column(columnDefinition = "TEXT")
  private String previousState;
  
  @Lob
  @Column(nullable = false, columnDefinition = "TEXT")
  private String newState;
  
  @Column(nullable = false, updatable = false)
  private Instant timestamp;
  
  public AuditEntry(EventType eventType, String entityType, UUID entityId, 
    UUID projectId, UUID actorUserId, UUID organisationId, String actorIpAddress,
    String previousState, String newState, Instant timestamp) {
    this.eventType = eventType;
    this.entityType = entityType;
    this.entityId = entityId;
    this.projectId = projectId;
    this.actorUserId = actorUserId;
    this.organisationId = organisationId;
    this.actorIpAddress = actorIpAddress;
    this.previousState = previousState;
    this.newState = newState;
    this.timestamp = timestamp;
  }
}
```

**Validation:**
- [ ] Model compiles without errors
- [ ] @Immutable annotation prevents modifications
- [ ] All fields properly annotated
- [ ] Constructors work correctly

**Step 2: Enhance Notification model**
```java
@Entity
@Table(name = "notifications", indexes = {
  @Index(name = "idx_notification_recipient", 
    columnList = "organisation_id,recipient_user_id,is_read,created_at DESC"),
  @Index(name = "idx_notification_expiry", columnList = "organisation_id,expires_at")
})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;
  
  @Column(nullable = false, name = "recipient_user_id")
  private UUID recipientUserId;
  
  @Column(nullable = false, name = "organisation_id")
  private UUID organisationId;
  
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private EventType eventType;
  
  @Column(nullable = false)
  private UUID projectId;
  
  @Column(nullable = false, length = 120)
  private String title;
  
  @Column(nullable = false, length = 500)
  private String message;
  
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private NotificationCategory category;
  
  @Column(nullable = false)
  private Integer priority;
  
  @Column(nullable = false, name = "is_read")
  private boolean read = false;
  
  @Column(name = "read_at")
  private Instant readAt;
  
  @Column(nullable = false, updatable = false)
  private Instant createdAt;
  
  @Column(nullable = false)
  private Instant expiresAt;
  
  @Column(name = "deleted_at")
  private Instant deletedAt;
  
  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (expiresAt == null) {
      expiresAt = createdAt.plus(Duration.ofDays(30));
    }
  }
  
  public void markRead() {
    this.read = true;
    this.readAt = Instant.now();
  }
  
  public void softDelete() {
    this.deletedAt = Instant.now();
  }
}
```

**Validation:**
- [ ] Model compiles
- [ ] Soft delete support in place
- [ ] TTL logic correct
- [ ] All constraints enforced

#### 1.3 Repository Implementation

**AuditEntryRepository:**
```java
@Repository
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {
  
  @Query("SELECT ae FROM AuditEntry ae " +
         "WHERE ae.organisationId = :organisationId " +
         "AND ae.projectId = :projectId " +
         "ORDER BY ae.timestamp DESC")
  Page<AuditEntry> findProjectHistory(
    UUID projectId, 
    UUID organisationId, 
    Pageable pageable
  );
  
  @Query("SELECT ae FROM AuditEntry ae " +
         "WHERE ae.organisationId = :organisationId " +
         "AND ae.entityType = :entityType " +
         "AND ae.entityId = :entityId " +
         "ORDER BY ae.timestamp DESC")
  List<AuditEntry> findEntityHistory(
    String entityType, 
    UUID entityId, 
    UUID organisationId
  );
}
```

**NotificationRepository:**
```java
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
  
  @Query("SELECT n FROM Notification n " +
         "WHERE n.organisationId = :organisationId " +
         "AND n.recipientUserId = :recipientUserId " +
         "AND n.read = false " +
         "AND n.deletedAt IS NULL " +
         "ORDER BY n.createdAt DESC")
  Page<Notification> findUnread(
    UUID recipientUserId, 
    UUID organisationId, 
    Pageable pageable
  );
  
  @Query("SELECT COUNT(n) FROM Notification n " +
         "WHERE n.organisationId = :organisationId " +
         "AND n.recipientUserId = :recipientUserId " +
         "AND n.read = false " +
         "AND n.deletedAt IS NULL")
  long countUnread(UUID recipientUserId, UUID organisationId);
  
  @Modifying
  @Query("UPDATE Notification n " +
         "SET n.read = true, n.readAt = CURRENT_TIMESTAMP " +
         "WHERE n.organisationId = :organisationId " +
         "AND n.recipientUserId = :recipientUserId " +
         "AND n.read = false")
  int markAllAsRead(UUID recipientUserId, UUID organisationId);
}
```

**Validation:**
- [ ] Both repositories compile
- [ ] Query methods tested with JUnit
- [ ] Custom queries execute correctly
- [ ] Pagination works as expected

#### 1.4 Service Implementation

**Enhanced AuditService:**
```java
@Service
public class AuditService {
  private static final Logger log = LoggerFactory.getLogger(AuditService.class);
  private final AuditEntryRepository repository;
  private final ObjectMapper objectMapper;
  
  public AuditService(AuditEntryRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }
  
  @Transactional
  public AuditResponse record(
    CreateAuditRequest request,
    TenantContext tenant
  ) {
    validateAuditRequest(request, tenant);
    
    AuditEntry entry = new AuditEntry(
      request.eventType(),
      request.entityType(),
      request.entityId(),
      request.projectId(),
      tenant.userId(),
      tenant.organisationId(),
      tenant.ipAddress(),
      request.previousState(),
      request.newState(),
      Instant.now()
    );
    
    entry = repository.save(entry);
    log.info("Audit recorded: eventType={}, entityType={}, entityId={}, actorId={}",
      entry.getEventType(), entry.getEntityType(), entry.getEntityId(), 
      tenant.userId());
    
    return toResponse(entry);
  }
  
  @Transactional(readOnly = true)
  public Page<AuditResponse> history(
    UUID projectId,
    Instant from,
    Instant to,
    EventType type,
    Pageable pageable,
    TenantContext tenant
  ) {
    validateHistoryQuery(from, to, pageable);
    
    Page<AuditEntry> entries = repository.findProjectHistory(
      projectId, 
      tenant.organisationId(), 
      pageable
    );
    
    return entries.map(this::toResponse);
  }
  
  private void validateAuditRequest(CreateAuditRequest req, TenantContext tenant) {
    if (req.eventType() == null) {
      throw new ValidationException("eventType required");
    }
    if (req.entityType() == null || req.entityType().isBlank()) {
      throw new ValidationException("entityType required");
    }
    if (!req.entityType().matches("^[A-Z0-9_]{1,60}$")) {
      throw new ValidationException("entityType format invalid");
    }
    if (req.newState() == null || req.newState().isBlank()) {
      throw new ValidationException("newState required");
    }
    validateJSON(req.previousState()); // nullable
    validateJSON(req.newState());      // required
  }
  
  private void validateJSON(String json) {
    if (json == null) return;
    try {
      objectMapper.readTree(json);
    } catch (Exception e) {
      throw new ValidationException("Invalid JSON: " + e.getMessage());
    }
  }
  
  private AuditResponse toResponse(AuditEntry entry) {
    return new AuditResponse(
      entry.getId(),
      entry.getEventType(),
      entry.getEntityType(),
      entry.getEntityId(),
      entry.getProjectId(),
      entry.getActorUserId(),
      entry.getOrganisationId(),
      entry.getActorIpAddress(),
      entry.getPreviousState(),
      entry.getNewState(),
      entry.getTimestamp()
    );
  }
}
```

**Enhanced NotificationService:**
```java
@Service
public class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private final NotificationRepository repository;
  
  public NotificationService(NotificationRepository repository) {
    this.repository = repository;
  }
  
  @Transactional
  public List<NotificationResponse> dispatch(
    Set<UUID> recipientIds,
    UUID projectId,
    EventType eventType,
    String message,
    UUID organisationId
  ) {
    validateDispatchRequest(recipientIds, message);
    
    List<Notification> notifications = recipientIds.stream()
      .map(id -> new Notification(id, organisationId, eventType, projectId, 
        eventType.name(), message))
      .map(repository::save)
      .toList();
    
    log.info("Notifications dispatched: count={}, eventType={}, recipients={}",
      notifications.size(), eventType, recipientIds.size());
    
    return notifications.stream()
      .map(this::toResponse)
      .toList();
  }
  
  @Transactional(readOnly = true)
  public Page<NotificationResponse> unread(
    UUID requestedUserId,
    TenantContext tenant,
    Pageable pageable
  ) {
    if (!requestedUserId.equals(tenant.userId())) {
      log.warn("Unauthorized unread notification access: userId={}, requested={}",
        tenant.userId(), requestedUserId);
      throw new ForbiddenException("Users may only read their own notifications");
    }
    
    Page<Notification> notifications = repository.findUnread(
      requestedUserId,
      tenant.organisationId(),
      pageable
    );
    
    return notifications.map(this::toResponse);
  }
  
  @Transactional
  public NotificationResponse markRead(
    UUID notificationId,
    TenantContext tenant
  ) {
    Notification notification = repository.findById(notificationId)
      .orElseThrow(() -> new NotFoundException("Notification not found"));
    
    if (!notification.getOrganisationId().equals(tenant.organisationId())) {
      throw new ForbiddenException("Notification not found");
    }
    
    if (!notification.getRecipientUserId().equals(tenant.userId())) {
      throw new ForbiddenException("Notification belongs to another user");
    }
    
    notification.markRead();
    repository.save(notification);
    
    log.debug("Notification marked as read: notificationId={}", notificationId);
    
    return toResponse(notification);
  }
  
  private void validateDispatchRequest(Set<UUID> recipientIds, String message) {
    if (recipientIds == null || recipientIds.isEmpty()) {
      throw new ValidationException("At least one recipient required");
    }
    if (recipientIds.size() > 1000) {
      throw new ValidationException("Maximum 1000 recipients per dispatch");
    }
    if (message == null || message.isBlank()) {
      throw new ValidationException("Message required");
    }
    if (message.length() > 500) {
      throw new ValidationException("Message exceeds 500 characters");
    }
  }
  
  private NotificationResponse toResponse(Notification n) {
    return new NotificationResponse(
      n.getId(),
      n.getRecipientUserId(),
      n.getEventType(),
      n.getProjectId(),
      n.getTitle(),
      n.getMessage(),
      n.getCategory(),
      n.getPriority(),
      n.isRead(),
      n.getReadAt(),
      n.getCreatedAt()
    );
  }
}
```

**Validation:**
- [ ] Services compile without errors
- [ ] All validation methods work correctly
- [ ] Transactions applied appropriately
- [ ] Logging is comprehensive

#### 1.5 Controller Implementation

**AuditController:**
```java
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
  private final AuditService service;
  private final TenantContextResolver resolver;
  
  public AuditController(AuditService service, TenantContextResolver resolver) {
    this.service = service;
    this.resolver = resolver;
  }
  
  @GetMapping("/history")
  Page<AuditResponse> history(
    @RequestParam UUID projectId,
    @RequestParam(required = false) Instant from,
    @RequestParam(required = false) Instant to,
    @RequestParam(required = false) EventType eventType,
    @RequestParam(defaultValue = "0") int offset,
    @RequestParam(defaultValue = "100") int limit,
    HttpServletRequest request
  ) {
    Pageable pageable = PageRequest.of(
      offset / limit,
      Math.min(limit, 1000),
      Sort.by("timestamp").descending()
    );
    
    return service.history(
      projectId, from, to, eventType, 
      pageable, 
      resolver.resolve(request)
    );
  }
}
```

**NotificationController:**
```java
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
  private final NotificationService service;
  private final TenantContextResolver resolver;
  
  public NotificationController(NotificationService service, TenantContextResolver resolver) {
    this.service = service;
    this.resolver = resolver;
  }
  
  @GetMapping("/unread")
  Page<NotificationResponse> unread(
    @RequestParam(defaultValue = "0") int offset,
    @RequestParam(defaultValue = "50") int limit,
    HttpServletRequest request
  ) {
    TenantContext tenant = resolver.resolve(request);
    Pageable pageable = PageRequest.of(
      offset / limit,
      Math.min(limit, 100),
      Sort.by("createdAt").descending()
    );
    
    return service.unread(tenant.userId(), tenant, pageable);
  }
  
  @PatchMapping("/{id}/read")
  NotificationResponse markRead(
    @PathVariable UUID id,
    HttpServletRequest request
  ) {
    return service.markRead(id, resolver.resolve(request));
  }
}
```

**Validation:**
- [ ] Controllers compile
- [ ] All endpoints respond correctly
- [ ] Pagination parameters validated
- [ ] Tenant context properly injected

#### 1.6 Unit Tests

**Test Coverage Requirements:**
- [ ] AuditService: 100% coverage
  - [ ] Record valid audit entry
  - [ ] Reject invalid eventType
  - [ ] Reject invalid JSON states
  - [ ] History query respects tenant isolation
  - [ ] History query respects time range

- [ ] NotificationService: 100% coverage
  - [ ] Dispatch to multiple recipients
  - [ ] Reject empty recipient list
  - [ ] Reject >1000 recipients
  - [ ] Mark as read (success case)
  - [ ] Mark as read (authorization check)
  - [ ] Unread notifications filtered by tenant

- [ ] Repositories: 100% coverage
  - [ ] Find by organisation
  - [ ] Find by recipient + read status
  - [ ] Soft delete filtering
  - [ ] Pagination working

**Example Test:**
```java
@Test
void testMarkReadRejectsUnauthorizedUser() {
  UUID notificationId = UUID.randomUUID();
  UUID ownerId = UUID.randomUUID();
  UUID hackerId = UUID.randomUUID();
  
  Notification notification = new Notification(
    ownerId, organisationId, EventType.MILESTONE_CREATED,
    projectId, "Title", "Message"
  );
  repository.save(notification);
  
  TenantContext hacker = new TenantContext(hackerId, organisationId, "127.0.0.1");
  
  assertThrows(ForbiddenException.class, () -> {
    service.markRead(notificationId, hacker);
  });
}
```

---

## Deployment Checklist

### Pre-Deployment
- [ ] All unit tests passing (100% coverage)
- [ ] Integration tests passing
- [ ] Load testing completed (10K events/sec)
- [ ] Security review passed
- [ ] Performance benchmarks met (< 200ms P99)
- [ ] Database migration tested in staging

### Deployment
- [ ] Backup current production database
- [ ] Apply database schema changes
- [ ] Deploy application code
- [ ] Verify audit entry creation
- [ ] Verify notification dispatch
- [ ] Smoke tests in production

### Post-Deployment
- [ ] Monitor error rates (should be 0)
- [ ] Monitor query latency (should be < 200ms)
- [ ] Verify tenant isolation (spot checks)
- [ ] Check database disk usage
- [ ] Verify backup completion

---

## Maintenance Procedures

### Daily
```sql
-- Monitor for errors
SELECT COUNT(*) as error_count
FROM audit_entries
WHERE timestamp > NOW() - INTERVAL 24 HOUR
AND event_type = 'ERROR';

-- Check notification queue depth
SELECT COUNT(*) as pending
FROM notifications
WHERE deleted_at IS NULL
AND expires_at > NOW();
```

### Weekly
```sql
-- Analyze table statistics
ANALYZE TABLE audit_entries;
ANALYZE TABLE notifications;

-- Check index usage
SELECT * FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE object_name IN ('audit_entries', 'notifications');
```

### Monthly
```sql
-- Archive old audit entries
INSERT INTO audit_entries_archive
SELECT * FROM audit_entries
WHERE YEAR(timestamp) < YEAR(CURDATE());

DELETE FROM audit_entries
WHERE YEAR(timestamp) < YEAR(CURDATE());

-- Clean up expired notifications
UPDATE notifications SET deleted_at = NOW()
WHERE expires_at < NOW() - INTERVAL 90 DAY;
```

---

## Success Criteria

Project is successful if:
- ✅ All audit entries properly recorded and immutable
- ✅ All notifications properly dispatched to correct recipients
- ✅ Tenant isolation verified (no cross-tenant data leakage)
- ✅ Query performance < 200ms P99
- ✅ 100% test coverage
- ✅ Zero security vulnerabilities found
- ✅ Compliance requirements met (SOC2, GDPR, HIPAA)
- ✅ Operational procedures documented and tested

---

**Document Control**
- Version: 1.0
- Status: Ready for implementation
- Last Updated: 2026-08-30
