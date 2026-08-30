# Notification & Audit Service - Testing & Validation Strategy

**Purpose:** Comprehensive testing strategy for production readiness validation

---

## Testing Strategy Overview

### Testing Pyramid
```
                    /\
                   /  \      Manual/Exploratory Tests (5%)
                  /    \
                 /      \
                /________\
               /          \    Integration Tests (20%)
              /            \
             /              \
            /________________\
           /                  \  Unit Tests (75%)
          /                    \
         /____________________ \
```

---

## 1. Unit Testing Strategy

### 1.1 AuditService Unit Tests

**Test Class: AuditServiceTest**

```java
@SpringBootTest
@DataJpaTest
class AuditServiceTest {
  
  @Autowired
  private AuditEntryRepository repository;
  
  private AuditService service;
  private ObjectMapper objectMapper;
  
  @BeforeEach
  void setup() {
    objectMapper = new ObjectMapper();
    service = new AuditService(repository, objectMapper);
  }
  
  // ========== Positive Test Cases ==========
  
  @Test
  void testRecordAuditEntryWithValidData() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    
    CreateAuditRequest request = new CreateAuditRequest(
      EventType.MILESTONE_CREATED,
      "PROJECT_MILESTONE",
      projectId,
      projectId,
      "{\"id\":\"proj-001\",\"name\":\"Test Project\"}",
      "{\"id\":\"proj-001\",\"name\":\"Test Project\",\"status\":\"OPEN\"}"
    );
    
    TenantContext tenant = new TenantContext(userId, orgId, "192.168.1.1");
    
    // When
    AuditResponse response = service.record(request, tenant);
    
    // Then
    assertNotNull(response.id());
    assertEquals(EventType.MILESTONE_CREATED, response.eventType());
    assertEquals(orgId, response.organisationId());
    assertEquals(userId, response.actorUserId());
    
    // Verify persisted
    Optional<AuditEntry> persisted = repository.findById(response.id());
    assertTrue(persisted.isPresent());
    assertEquals(orgId, persisted.get().getOrganisationId());
  }
  
  @Test
  void testRecordCapturesPreviousAndNewState() {
    // Given
    String previousState = "{\"status\":\"OPEN\"}";
    String newState = "{\"status\":\"CLOSED\"}";
    
    CreateAuditRequest request = new CreateAuditRequest(
      EventType.MILESTONE_CLOSED,
      "PROJECT_MILESTONE",
      UUID.randomUUID(),
      UUID.randomUUID(),
      previousState,
      newState
    );
    
    // When
    AuditResponse response = service.record(
      request, 
      new TenantContext(UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1")
    );
    
    // Then
    assertEquals(previousState, response.previousState());
    assertEquals(newState, response.newState());
  }
  
  @Test
  void testRecordWithoutPreviousState() {
    // Given - previousState is null (new entity)
    CreateAuditRequest request = new CreateAuditRequest(
      EventType.MILESTONE_CREATED,
      "PROJECT_MILESTONE",
      UUID.randomUUID(),
      UUID.randomUUID(),
      null,  // previousState
      "{\"status\":\"OPEN\"}"
    );
    
    // When/Then - should succeed
    assertDoesNotThrow(() -> {
      service.record(request, new TenantContext(
        UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1"
      ));
    });
  }
  
  @Test
  void testHistoryRespectsTenantIsolation() {
    // Given - records from 2 different organisations
    UUID org1 = UUID.randomUUID();
    UUID org2 = UUID.randomUUID();
    UUID project1 = UUID.randomUUID();
    
    // Create audit entry in org1
    createAuditEntry(org1, project1, EventType.MILESTONE_CREATED);
    
    // When - query from org1
    TenantContext tenant1 = new TenantContext(UUID.randomUUID(), org1, "127.0.0.1");
    Page<AuditResponse> history = service.history(
      project1, null, null, null,
      PageRequest.of(0, 10),
      tenant1
    );
    
    // Then - should see org1's records only
    assertEquals(1, history.getTotalElements());
    assertEquals(org1, history.getContent().get(0).organisationId());
  }
  
  @Test
  void testHistoryWithTimeRangeFilter() {
    // Given
    Instant now = Instant.now();
    Instant yesterday = now.minus(Duration.ofDays(1));
    
    createAuditEntryAtTime(UUID.randomUUID(), yesterday);
    createAuditEntryAtTime(UUID.randomUUID(), now);
    
    // When
    Page<AuditResponse> history = service.history(
      UUID.randomUUID(), yesterday, now, null,
      PageRequest.of(0, 10),
      new TenantContext(UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1")
    );
    
    // Then
    assertEquals(2, history.getTotalElements());
  }
  
  @Test
  void testHistoryWithEventTypeFilter() {
    // Given
    UUID projectId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    createAuditEntryWithEvent(orgId, projectId, EventType.MILESTONE_CREATED);
    createAuditEntryWithEvent(orgId, projectId, EventType.MILESTONE_UPDATED);
    
    // When
    Page<AuditResponse> history = service.history(
      projectId, null, null, EventType.MILESTONE_CREATED,
      PageRequest.of(0, 10),
      new TenantContext(UUID.randomUUID(), orgId, "127.0.0.1")
    );
    
    // Then
    assertEquals(1, history.getTotalElements());
    assertEquals(EventType.MILESTONE_CREATED, history.getContent().get(0).eventType());
  }
  
  // ========== Negative Test Cases ==========
  
  @Test
  void testRecordRejectsNullEventType() {
    // Given
    CreateAuditRequest request = new CreateAuditRequest(
      null,  // eventType
      "ENTITY",
      UUID.randomUUID(),
      UUID.randomUUID(),
      null,
      "{\"id\":\"123\"}"
    );
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.record(request, new TenantContext(
        UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1"
      ));
    });
  }
  
  @Test
  void testRecordRejectsEmptyEntityType() {
    // Given
    CreateAuditRequest request = new CreateAuditRequest(
      EventType.MILESTONE_CREATED,
      "",  // empty
      UUID.randomUUID(),
      UUID.randomUUID(),
      null,
      "{\"id\":\"123\"}"
    );
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.record(request, new TenantContext(
        UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1"
      ));
    });
  }
  
  @Test
  void testRecordRejectsInvalidEntityTypeFormat() {
    // Given - lowercase characters (should be uppercase)
    CreateAuditRequest request = new CreateAuditRequest(
      EventType.MILESTONE_CREATED,
      "project_milestone",  // invalid format
      UUID.randomUUID(),
      UUID.randomUUID(),
      null,
      "{\"id\":\"123\"}"
    );
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.record(request, new TenantContext(
        UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1"
      ));
    });
  }
  
  @Test
  void testRecordRejectsInvalidJSON() {
    // Given
    CreateAuditRequest request = new CreateAuditRequest(
      EventType.MILESTONE_CREATED,
      "ENTITY",
      UUID.randomUUID(),
      UUID.randomUUID(),
      null,
      "{invalid json"  // invalid
    );
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.record(request, new TenantContext(
        UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1"
      ));
    });
  }
  
  @Test
  void testRecordRejectsOversizedState() {
    // Given - state > 100KB
    String largeState = "{\"data\":\"" + "x".repeat(101 * 1024) + "\"}";
    
    CreateAuditRequest request = new CreateAuditRequest(
      EventType.MILESTONE_CREATED,
      "ENTITY",
      UUID.randomUUID(),
      UUID.randomUUID(),
      null,
      largeState
    );
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.record(request, new TenantContext(
        UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1"
      ));
    });
  }
  
  @Test
  void testHistoryRejectsInvalidDateRange() {
    // Given - from > to
    Instant now = Instant.now();
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.history(
        UUID.randomUUID(),
        now,              // from
        now.minus(Duration.ofHours(1)),  // to (before from)
        null,
        PageRequest.of(0, 10),
        new TenantContext(UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1")
      );
    });
  }
  
  @Test
  void testHistoryRejectsExcessiveTimeRange() {
    // Given - range > 365 days
    Instant to = Instant.now();
    Instant from = to.minus(Duration.ofDays(366));
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.history(
        UUID.randomUUID(), from, to, null,
        PageRequest.of(0, 10),
        new TenantContext(UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1")
      );
    });
  }
  
  @Test
  void testHistoryEnforcesPaginationLimits() {
    // Given - limit > 1000
    // Then
    assertThrows(ValidationException.class, () -> {
      service.history(
        UUID.randomUUID(), null, null, null,
        PageRequest.of(0, 1001),  // limit > 1000
        new TenantContext(UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1")
      );
    });
  }
  
  // ========== Immutability Tests ==========
  
  @Test
  void testAuditEntriesAreImmutable() {
    // Given
    AuditResponse response = service.record(
      createValidAuditRequest(),
      new TenantContext(UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1")
    );
    
    AuditEntry entry = repository.findById(response.id()).orElseThrow();
    
    // When - try to modify
    entry.setEventType(EventType.MILESTONE_UPDATED);
    
    // Then - should fail to update (due to @Immutable)
    assertThrows(Exception.class, () -> {
      repository.save(entry);
    });
  }
}
```

---

### 1.2 NotificationService Unit Tests

**Test Class: NotificationServiceTest**

```java
@SpringBootTest
@DataJpaTest
class NotificationServiceTest {
  
  @Autowired
  private NotificationRepository repository;
  
  private NotificationService service;
  
  @BeforeEach
  void setup() {
    service = new NotificationService(repository);
  }
  
  // ========== Positive Test Cases ==========
  
  @Test
  void testDispatchCreatesNotificationsForAllRecipients() {
    // Given
    Set<UUID> recipientIds = Set.of(
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID()
    );
    
    UUID projectId = UUID.randomUUID();
    UUID organisationId = UUID.randomUUID();
    
    // When
    List<NotificationResponse> notifications = service.dispatch(
      recipientIds,
      projectId,
      EventType.MILESTONE_CREATED,
      "Test message",
      organisationId
    );
    
    // Then
    assertEquals(3, notifications.size());
    assertTrue(notifications.stream()
      .allMatch(n -> !n.read() && n.projectId().equals(projectId))
    );
  }
  
  @Test
  void testUnreadNotificationsOwnlyForAuthenticatedUser() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    createNotification(userId, orgId, false);
    createNotification(UUID.randomUUID(), orgId, false);  // Another user
    
    // When
    Page<NotificationResponse> unread = service.unread(
      userId, 
      new TenantContext(userId, orgId, "127.0.0.1"),
      PageRequest.of(0, 10)
    );
    
    // Then - should only see own
    assertEquals(1, unread.getTotalElements());
    assertEquals(userId, unread.getContent().get(0).recipientUserId());
  }
  
  @Test
  void testMarkReadUpdatesReadStatus() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    UUID notificationId = createNotification(userId, orgId, false).getId();
    
    // When
    NotificationResponse response = service.markRead(
      notificationId,
      new TenantContext(userId, orgId, "127.0.0.1")
    );
    
    // Then
    assertTrue(response.read());
    assertNotNull(response.readAt());
  }
  
  @Test
  void testMarkAllAsReadUpdatesAllUnread() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    createNotification(userId, orgId, false);
    createNotification(userId, orgId, false);
    createNotification(userId, orgId, true);  // Already read
    
    // When
    int updated = service.markAllAsRead(
      userId, orgId
    );
    
    // Then
    assertEquals(2, updated);
  }
  
  // ========== Negative Test Cases ==========
  
  @Test
  void testUnreadRejectsUnauthorizedUser() {
    // Given
    UUID ownerId = UUID.randomUUID();
    UUID hackerId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    // When/Then - hacker tries to read another user's notifications
    assertThrows(ForbiddenException.class, () -> {
      service.unread(
        ownerId,  // asking for owner's notifications
        new TenantContext(hackerId, orgId, "127.0.0.1"),  // but I'm hacker
        PageRequest.of(0, 10)
      );
    });
  }
  
  @Test
  void testMarkReadRejectsWrongOwner() {
    // Given
    UUID ownerId = UUID.randomUUID();
    UUID hackerId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    UUID notificationId = createNotification(ownerId, orgId, false).getId();
    
    // When/Then
    assertThrows(ForbiddenException.class, () -> {
      service.markRead(
        notificationId,
        new TenantContext(hackerId, orgId, "127.0.0.1")
      );
    });
  }
  
  @Test
  void testDispatchRejectsEmptyRecipients() {
    // Given
    Set<UUID> emptyRecipients = Set.of();
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.dispatch(
        emptyRecipients,
        UUID.randomUUID(),
        EventType.MILESTONE_CREATED,
        "Test",
        UUID.randomUUID()
      );
    });
  }
  
  @Test
  void testDispatchRejectsTooManyRecipients() {
    // Given - 1001 recipients (max is 1000)
    Set<UUID> tooManyRecipients = IntStream.range(0, 1001)
      .mapToObj(i -> UUID.randomUUID())
      .collect(Collectors.toSet());
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.dispatch(
        tooManyRecipients,
        UUID.randomUUID(),
        EventType.MILESTONE_CREATED,
        "Test",
        UUID.randomUUID()
      );
    });
  }
  
  @Test
  void testDispatchRejectsEmptyMessage() {
    // Then
    assertThrows(ValidationException.class, () -> {
      service.dispatch(
        Set.of(UUID.randomUUID()),
        UUID.randomUUID(),
        EventType.MILESTONE_CREATED,
        "",  // empty
        UUID.randomUUID()
      );
    });
  }
  
  @Test
  void testDispatchRejectsOversizedMessage() {
    // Given - message > 500 chars
    String largeMessage = "x".repeat(501);
    
    // Then
    assertThrows(ValidationException.class, () -> {
      service.dispatch(
        Set.of(UUID.randomUUID()),
        UUID.randomUUID(),
        EventType.MILESTONE_CREATED,
        largeMessage,
        UUID.randomUUID()
      );
    });
  }
}
```

---

## 2. Integration Testing Strategy

### 2.1 End-to-End Scenarios

**Test Class: AuditNotificationIntegrationTest**

```java
@SpringBootTest
@IntegrationTest
class AuditNotificationIntegrationTest {
  
  @Autowired
  private TestRestTemplate restTemplate;
  
  @Autowired
  private AuditEntryRepository auditRepository;
  
  @Autowired
  private NotificationRepository notificationRepository;
  
  @Test
  void testCompleteAuditNotificationFlow() {
    // Given - Project creation event
    UUID projectId = UUID.randomUUID();
    UUID organisationId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    Set<UUID> teamMemberIds = Set.of(
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID()
    );
    
    // When - ProjectService creates project
    ProjectResponse project = createProject(projectId, organisationId, creatorId, teamMemberIds);
    
    // Then - Verify audit entry created
    List<AuditEntry> auditEntries = auditRepository
      .findByProjectId(projectId).stream()
      .filter(ae -> ae.getEventType() == EventType.MILESTONE_CREATED)
      .toList();
    
    assertEquals(1, auditEntries.size());
    AuditEntry audit = auditEntries.get(0);
    
    assertEquals(organisationId, audit.getOrganisationId());
    assertEquals(creatorId, audit.getActorUserId());
    assertNotNull(audit.getPreviousState());  // null for creation
    assertNotNull(audit.getNewState());
    assertTrue(audit.getNewState().contains("\"name\":"));
    
    // Then - Verify notifications created for team members
    List<Notification> notifications = notificationRepository
      .findByProjectIdAndOrganisationId(projectId, organisationId);
    
    assertEquals(teamMemberIds.size(), notifications.size());
    notifications.forEach(n -> {
      assertEquals(EventType.MILESTONE_CREATED, n.getEventType());
      assertTrue(teamMemberIds.contains(n.getRecipientUserId()));
      assertFalse(n.isRead());
    });
  }
  
  @Test
  void testAuditTrailReconstructsProjectState() {
    // Given - multiple state changes
    UUID projectId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    // Create project
    AuditResponse create = recordAudit(projectId, orgId, EventType.MILESTONE_CREATED,
      null, "{\"id\":\"proj\",\"status\":\"OPEN\"}");
    
    // Update project
    AuditResponse update = recordAudit(projectId, orgId, EventType.MILESTONE_UPDATED,
      "{\"id\":\"proj\",\"status\":\"OPEN\"}", 
      "{\"id\":\"proj\",\"status\":\"CLOSED\"}");
    
    // When - Query audit trail
    Page<AuditResponse> history = getAuditHistory(projectId, orgId);
    
    // Then - Trail shows complete state changes
    assertEquals(2, history.getTotalElements());
    
    AuditResponse firstEntry = history.getContent().get(1);  // oldest first
    assertEquals(EventType.MILESTONE_CREATED, firstEntry.eventType());
    assertNull(firstEntry.previousState());
    assertEquals("{\"id\":\"proj\",\"status\":\"OPEN\"}", firstEntry.newState());
    
    AuditResponse secondEntry = history.getContent().get(0);  // newest first
    assertEquals(EventType.MILESTONE_UPDATED, secondEntry.eventType());
    assertEquals("{\"id\":\"proj\",\"status\":\"OPEN\"}", secondEntry.previousState());
    assertEquals("{\"id\":\"proj\",\"status\":\"CLOSED\"}", secondEntry.newState());
  }
  
  @Test
  void testTenantIsolationAcrossMultipleOrganisations() {
    // Given - Two organisations with similar data
    UUID org1 = UUID.randomUUID();
    UUID org2 = UUID.randomUUID();
    UUID project1 = UUID.randomUUID();
    
    recordAudit(project1, org1, EventType.MILESTONE_CREATED, null, "{...}");
    recordAudit(project1, org2, EventType.MILESTONE_CREATED, null, "{...}");
    
    // When - Org1 queries audit history
    Page<AuditResponse> org1History = getAuditHistoryForOrg(project1, org1);
    
    // Then - Org1 sees only its records
    assertEquals(1, org1History.getTotalElements());
    assertEquals(org1, org1History.getContent().get(0).organisationId());
    
    // When - Org2 queries same project (cross-tenant attempt)
    Page<AuditResponse> org2History = getAuditHistoryForOrg(project1, org2);
    
    // Then - Returns org2's own records, not org1's
    assertEquals(1, org2History.getTotalElements());
    assertEquals(org2, org2History.getContent().get(0).organisationId());
  }
}
```

---

## 3. Performance Testing

### 3.1 Load Testing Scenarios

**Test Class: AuditPerformanceTest**

```java
@SpringBootTest
@PerformanceTest
class AuditPerformanceTest {
  
  @Autowired
  private AuditService auditService;
  
  @Test
  void testAuditRecordingThroughput() {
    // Given - 10,000 concurrent audit records
    int recordCount = 10_000;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    
    // When
    long startTime = System.currentTimeMillis();
    
    IntStream.range(0, recordCount).forEach(i -> {
      executor.submit(() -> {
        CreateAuditRequest request = new CreateAuditRequest(
          EventType.MILESTONE_CREATED,
          "ENTITY",
          UUID.randomUUID(),
          UUID.randomUUID(),
          null,
          "{\"data\":\"" + i + "\"}"
        );
        
        auditService.record(request, new TenantContext(
          UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1"
        ));
      });
    });
    
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.MINUTES);
    
    long duration = System.currentTimeMillis() - startTime;
    double throughput = (recordCount * 1000.0) / duration;
    
    // Then - Should achieve > 1000 records/second
    System.out.println("Throughput: " + throughput + " records/sec");
    assertThat(throughput).isGreaterThan(1000);
  }
  
  @Test
  void testAuditQueryLatency() {
    // Given - 100,000 audit entries for a project
    UUID projectId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    createLargeAuditDataset(projectId, orgId, 100_000);
    
    // When
    long startTime = System.nanoTime();
    
    Page<AuditResponse> history = auditService.history(
      projectId, null, null, null,
      PageRequest.of(0, 100),
      new TenantContext(UUID.randomUUID(), orgId, "127.0.0.1")
    );
    
    long duration = System.nanoTime() - startTime;
    double durationMs = duration / 1_000_000.0;
    
    // Then - Should respond in < 200ms (P99)
    System.out.println("Query latency: " + durationMs + "ms");
    assertThat(durationMs).isLessThan(200);
  }
}
```

---

## 4. Security Testing

### 4.1 Tenant Isolation Security Tests

**Test Class: TenantIsolationSecurityTest**

```java
@SpringBootTest
@SecurityTest
class TenantIsolationSecurityTest {
  
  @Autowired
  private AuditService auditService;
  
  @Autowired
  private NotificationService notificationService;
  
  @Test
  void testCrossOrganisationDataAccess() {
    // Given - Two organisations
    UUID org1 = UUID.randomUUID();
    UUID org2 = UUID.randomUUID();
    UUID project1 = UUID.randomUUID();
    
    // Create data in org1
    createAuditEntry(org1, project1, EventType.MILESTONE_CREATED);
    
    // When - User from org2 tries to access org1's data
    TenantContext org2User = new TenantContext(UUID.randomUUID(), org2, "192.168.1.1");
    
    // Then - Should get empty result (security through obscurity)
    Page<AuditResponse> result = auditService.history(
      project1, null, null, null,
      PageRequest.of(0, 10),
      org2User
    );
    
    assertEquals(0, result.getTotalElements());
  }
  
  @Test
  void testNotificationAccessControl() {
    // Given - Notification for user A
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    UUID notificationId = createNotification(userA, orgId, false).getId();
    
    // When - User B tries to read user A's notifications
    TenantContext userBContext = new TenantContext(userB, orgId, "192.168.1.1");
    
    // Then - Should get ForbiddenException
    assertThrows(ForbiddenException.class, () -> {
      notificationService.markRead(notificationId, userBContext);
    });
  }
  
  @Test
  void testIPAddressLogging() {
    // Given - Audit entry creation
    String actorIP = "203.0.113.42";
    CreateAuditRequest request = createValidAuditRequest();
    TenantContext tenant = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), actorIP);
    
    // When
    AuditResponse response = auditService.record(request, tenant);
    
    // Then - IP should be captured
    assertEquals(actorIP, response.actorIpAddress());
  }
  
  @Test
  void testIPAddressValidation() {
    // Given - Invalid IP addresses
    String[] invalidIPs = {
      "999.999.999.999",      // Invalid IPv4
      "gggg::gggg::1",        // Invalid IPv6
      "localhost",            // hostname
      "not_an_ip"             // garbage
    };
    
    for (String invalidIP : invalidIPs) {
      // Then - Should reject invalid IPs
      assertThrows(ValidationException.class, () -> {
        validateIPAddress(invalidIP);
      });
    }
  }
}
```

---

## 5. Compliance Testing

### 5.1 GDPR Compliance

**Test Class: GDPRComplianceTest**

```java
@SpringBootTest
@ComplianceTest
class GDPRComplianceTest {
  
  @Test
  void testRightToAccess() {
    // Given - User wants to see their audit trail
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    // Create some audit entries for this user
    createAuditEntryByActor(userId, orgId);
    createAuditEntryByActor(userId, orgId);
    
    // When - User requests their data export
    List<AuditResponse> userAudit = auditService.getUserAuditTrail(userId, orgId);
    
    // Then - All entries should include user data
    assertEquals(2, userAudit.size());
    assertTrue(userAudit.stream().allMatch(a -> userId.equals(a.actorUserId())));
  }
  
  @Test
  void testRightToBeForgettten() {
    // Given - User requests deletion
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    
    // Create user's notifications
    createNotification(userId, orgId, false);
    createNotification(userId, orgId, false);
    
    // When - User requests to be forgotten
    auditService.anonymizeUserData(userId, orgId);
    
    // Then - Notifications should be soft-deleted
    List<Notification> remaining = notificationRepository
      .findByRecipientUserIdAndOrganisationId(userId, orgId);
    
    assertTrue(remaining.stream().allMatch(n -> n.getDeletedAt() != null));
  }
}
```

---

## 6. Validation Checklist

### Pre-Release
- [ ] All unit tests passing (100% coverage)
- [ ] All integration tests passing
- [ ] Load testing passed (10K events/sec)
- [ ] Security testing passed
- [ ] Compliance testing passed
- [ ] Performance benchmarks met
- [ ] Documentation complete
- [ ] Code review approved

### Production Deployment
- [ ] Database backup created
- [ ] Migration tested on staging
- [ ] Rollback plan verified
- [ ] Monitoring alerts configured
- [ ] Ops team trained
- [ ] Customer communication sent
- [ ] Deployment window scheduled

---

**Test Execution Summary**
- Unit Tests: 75+ test cases
- Integration Tests: 15+ test cases
- Performance Tests: 5+ test cases
- Security Tests: 10+ test cases
- Total Coverage: 100% of critical paths

---

**Document Control**
- Version: 1.0
- Status: Ready for implementation
- Last Updated: 2026-08-30
