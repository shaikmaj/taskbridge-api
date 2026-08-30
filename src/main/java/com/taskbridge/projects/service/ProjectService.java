package com.taskbridge.projects.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskbridge.common.*;
import com.taskbridge.notifications.dto.CreateAuditRequest;
import com.taskbridge.notifications.model.EventType;
import com.taskbridge.notifications.service.*;
import com.taskbridge.projects.dto.*;
import com.taskbridge.projects.model.*;
import com.taskbridge.projects.repository.ProjectRepository;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer for Project business logic.
 *
 * <p>Responsibilities:
 * - Project lifecycle management (create, update, delete)
 * - Tenant isolation enforcement
 * - Authorization checks
 * - Input validation
 * - Audit event emission
 * - Structured logging
 *
 * <p>Security notes:
 * - All database queries include soft-delete filter
 * - Tenant ID is verified on all operations
 * - Authorization checks prevent cross-tenant data access
 * - Audit trail captures all state changes
 */
@Service
public class ProjectService {
  private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
  private static final String LOG_CONTEXT_ORG = "organisationId";
  private static final String LOG_CONTEXT_USER = "userId";

  private final ProjectRepository repository;
  private final AuditService auditService;
  private final NotificationService notificationService;
  private final ObjectMapper objectMapper;

  public ProjectService(
    ProjectRepository repository,
    AuditService auditService,
    NotificationService notificationService,
    ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.auditService = auditService;
    this.notificationService = notificationService;
    this.objectMapper = objectMapper;
  }

  /**
   * Creates a new project within the tenant's organisation.
   *
   * @param request Project creation request with validated name and team members
   * @param tenant Current tenant context
   * @return Created project response
   * @throws IllegalArgumentException if validation fails
   */
  @Transactional
  public ProjectResponse create(CreateProjectRequest request, TenantContext tenant) {
    setTenantLogContext(tenant);
    log.info("Creating project name={}", request.name());

    try {
      // Validate request
      validateCreateRequest(request, tenant);

      // Create project entity
      Project project = new Project();
      project.setOrganisationId(tenant.organisationId());
      project.setName(request.name().trim());
      project.setStatus(ProjectStatus.OPEN);
      project.setTeamMemberIds(new HashSet<>(request.teamMemberIds()));

      // Persist
      project = repository.save(project);
      log.info("Project created projectId={} teamSize={}", project.getId(), project.getTeamMemberIds().size());

      // Emit audit and notification events
      emitProjectEvent(project, null, EventType.MILESTONE_CREATED, tenant);

      return toResponse(project, tenant);
    } catch (Exception e) {
      log.error("Failed to create project name={}", request.name(), e);
      throw e;
    }
  }

  /**
   * Updates the status of an existing project with idempotency check.
   *
   * @param projectId Project ID to update
   * @param request Status update request
   * @param tenant Current tenant context
   * @return Updated project response
   * @throws NotFoundException if project not found
   * @throws ForbiddenException if tenant is not authorised
   */
  @Transactional
  public ProjectResponse updateStatus(
    UUID projectId,
    UpdateProjectStatusRequest request,
    TenantContext tenant
  ) {
    setTenantLogContext(tenant);
    log.info("Updating project status projectId={} newStatus={}", projectId, request.status());

    try {
      Project project = getAuthorisedProject(projectId, tenant);
      ProjectStatus oldStatus = project.getStatus();

      // Idempotency check: if already in target state, return without duplicate audit
      if (oldStatus.equals(request.status())) {
        log.debug("Project already in status {}, returning without update", request.status());
        return toResponse(project, tenant);
      }

      // Capture previous state for audit
      String previousState = serializeProject(project);

      // Update status
      project.setStatus(request.status());
      project = repository.save(project);

      // Determine event type based on status transition
      EventType eventType = mapStatusTransitionToEventType(oldStatus, request.status());
      log.info(
        "Project status updated projectId={} oldStatus={} newStatus={} eventType={}",
        projectId,
        oldStatus,
        request.status(),
        eventType
      );

      // Emit audit and notification events
      emitProjectEvent(project, previousState, eventType, tenant);

      return toResponse(project, tenant);
    } catch (Exception e) {
      log.error("Failed to update project status projectId={}", projectId, e);
      throw e;
    }
  }

  /**
   * Retrieves a single project for the current tenant.
   *
   * @param projectId Project ID
   * @param tenant Current tenant context
   * @return Project response
   * @throws NotFoundException if project not found or deleted
   */
  @Transactional(readOnly = true)
  public ProjectResponse get(UUID projectId, TenantContext tenant) {
    setTenantLogContext(tenant);
    log.debug("Fetching project projectId={}", projectId);

    Project project = getAuthorisedProject(projectId, tenant);
    return toResponse(project, tenant);
  }

  /**
   * Retrieves all projects assigned to a team member within the tenant.
   *
   * <p>Authorization: Only the team member themselves or organisation admins can view their
   * projects. This prevents users from enumerating other employees' project portfolios.
   *
   * @param teamMemberId UUID of team member to query projects for
   * @param tenant Current tenant context
   * @return List of project responses
   * @throws ForbiddenException if caller is not authorised to view this team member's projects
   */
  @Transactional(readOnly = true)
  public List<ProjectResponse> getByTeam(UUID teamMemberId, TenantContext tenant) {
    setTenantLogContext(tenant);
    log.info("Fetching projects for team member teamMemberId={}", teamMemberId);

    // CRITICAL: Authorization check - prevent users from querying other users' projects
    authoriseTeamMemberQuery(teamMemberId, tenant);

    List<Project> projects =
      repository.findByOrganisationIdAndTeamMemberIdsContainingAndDeletedFalse(
        tenant.organisationId(),
        teamMemberId
      );

    log.debug("Found {} projects for team member", projects.size());
    return projects.stream().map(p -> toResponse(p, tenant)).toList();
  }

  /**
   * Soft-deletes a project. The record remains in the database for audit trail but is excluded
   * from all queries.
   *
   * @param projectId Project ID to delete
   * @param tenant Current tenant context
   * @throws NotFoundException if project not found or already deleted
   */
  @Transactional
  public void delete(UUID projectId, TenantContext tenant) {
    setTenantLogContext(tenant);
    log.info("Deleting project projectId={}", projectId);

    try {
      Project project = getAuthorisedProject(projectId, tenant);

      // Capture state before soft delete
      String previousState = serializeProject(project);

      // Soft delete
      project.softDelete();
      repository.save(project);

      log.info("Project soft-deleted projectId={} deletedAt={}", projectId, project.getDeletedAt());

      // Emit audit event - capture final state after soft delete
      String finalState = serializeProject(project);
      emitProjectEvent(project, previousState, EventType.MILESTONE_DELETED, tenant, finalState);
    } catch (Exception e) {
      log.error("Failed to delete project projectId={}", projectId, e);
      throw e;
    }
  }

  // ==================== Private Validation Methods ====================

  /**
   * Validates create request before project creation.
   *
   * @throws IllegalArgumentException if validation fails
   */
  private void validateCreateRequest(CreateProjectRequest request, TenantContext tenant) {
    // Name validation (additional to annotation-based validation)
    String trimmedName = request.name().trim();
    if (trimmedName.isEmpty()) {
      throw new IllegalArgumentException("Project name cannot be whitespace-only after trim");
    }

    // Team members validation
    if (request.teamMemberIds().isEmpty()) {
      throw new IllegalArgumentException("At least one team member is required");
    }

    if (request.teamMemberIds().size() > 50) {
      throw new IllegalArgumentException("Maximum 50 team members per project");
    }

    // Verify team members exist and belong to organisation
    // TODO: Query user service to validate team member UUIDs
    // For now, perform basic null/empty checks
    for (UUID memberId : request.teamMemberIds()) {
      if (memberId == null) {
        throw new IllegalArgumentException("Team member ID cannot be null");
      }
    }

    log.debug("Validation passed for create request");
  }

  /**
   * Authorization check: Verifies caller is authorised to query projects for the given team
   * member.
   *
   * <p>Rules:
   * - User can query their own projects
   * - Organisation admins can query any member's projects
   * - Cross-tenant queries are rejected by getAuthorisedProject
   *
   * @throws ForbiddenException if not authorised
   */
  private void authoriseTeamMemberQuery(UUID teamMemberId, TenantContext tenant) {
    // Allow self-queries
    if (teamMemberId.equals(tenant.userId())) {
      log.debug("Self-query authorised");
      return;
    }

    // TODO: Check if caller is organisation admin
    // For now, reject cross-user queries
    throw new ForbiddenException(
      "Not authorised to view projects for user " + teamMemberId
    );
  }

  // ==================== Private Data Access Methods ====================

  /**
   * Retrieves a project and verifies it belongs to the current tenant.
   *
   * @throws NotFoundException if project not found or soft-deleted
   * @throws IllegalStateException if tenant boundary violation detected
   */
  private Project getAuthorisedProject(UUID projectId, TenantContext tenant) {
    Project project = repository.findByIdAndOrganisationIdAndDeletedFalse(
      projectId,
      tenant.organisationId()
    )
      .orElseThrow(() -> {
        log.warn("Project not found projectId={} organisationId={}", projectId, tenant.organisationId());
        return new NotFoundException("Project not found");
      });

    // Tenant boundary verification (defensive programming)
    if (!project.getOrganisationId().equals(tenant.organisationId())) {
      log.error("TENANT_BOUNDARY_VIOLATION projectId={} expectedOrg={} actualOrg={}",
        projectId, tenant.organisationId(), project.getOrganisationId());
      throw new IllegalStateException("Tenant boundary violation detected");
    }

    return project;
  }

  // ==================== Private Event Emission ====================

  /**
   * Emits audit and notification events for a project state change.
   */
  private void emitProjectEvent(
    Project project,
    String previousState,
    EventType eventType,
    TenantContext tenant
  ) {
    String currentState = serializeProject(project);
    emitProjectEvent(project, previousState, eventType, tenant, currentState);
  }

  /**
   * Emits audit and notification events with explicit state snapshots.
   *
   * <p>Security note: Audit messages do not include unsanitised user input. Event type is used
   * instead of project name to prevent injection attacks.
   */
  private void emitProjectEvent(
    Project project,
    String previousState,
    EventType eventType,
    TenantContext tenant,
    String currentState
  ) {
    try {
      // Record audit entry with state snapshots
      auditService.record(
        new CreateAuditRequest(
          eventType,
          "PROJECT_MILESTONE",
          project.getId(),
          project.getId(),
          previousState,
          currentState
        ),
        tenant
      );

      // Dispatch notifications (use event type, not project name to avoid injection)
      String eventDescription = formatEventDescription(eventType);
      notificationService.dispatch(
        project.getTeamMemberIds(),
        project.getId(),
        eventType,
        eventDescription,
        project.getOrganisationId()
      );

      log.debug("Event emitted projectId={} eventType={}", project.getId(), eventType);
    } catch (Exception e) {
      log.error("Failed to emit project event projectId={} eventType={}", project.getId(), eventType, e);
      // Don't rethrow - audit/notification failures shouldn't block project operations
    }
  }

  /**
   * Maps project status transitions to appropriate event types.
   */
  private EventType mapStatusTransitionToEventType(
    ProjectStatus oldStatus,
    ProjectStatus newStatus
  ) {
    if (newStatus == ProjectStatus.CLOSED) {
      return EventType.MILESTONE_CLOSED;
    }
    if (oldStatus == ProjectStatus.CLOSED && newStatus != ProjectStatus.CLOSED) {
      return EventType.MILESTONE_REOPENED;
    }
    return EventType.MILESTONE_UPDATED;
  }

  /**
   * Generates safe event descriptions for notifications.
   * Does not include user-provided data to prevent injection.
   */
  private String formatEventDescription(EventType eventType) {
    return switch (eventType) {
      case MILESTONE_CREATED -> "Project was created";
      case MILESTONE_CLOSED -> "Project was closed";
      case MILESTONE_REOPENED -> "Project was reopened";
      case MILESTONE_UPDATED -> "Project was updated";
      case MILESTONE_DELETED -> "Project was deleted";
      default -> "Project event: " + eventType;
    };
  }

  // ==================== Private Serialization ====================

  /**
   * Serializes project to JSON for audit trail.
   *
   * @throws IllegalStateException if serialisation fails
   */
  private String serializeProject(Project project) {
    try {
      return objectMapper.writeValueAsString(toResponse(project, null));
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize project projectId={}", project.getId(), e);
      throw new IllegalStateException("Could not serialise project snapshot", e);
    }
  }

  /**
   * Converts Project entity to response DTO.
   *
   * <p>Security notes:
   * - Tenant boundary verified before conversion
   * - organisationId NOT exposed in response (redundant given request context)
   * - teamMemberIds filtered based on permissions (future enhancement)
   *
   * @param project Project entity
   * @param tenant Current tenant context (null for audit serialization)
   * @return Project response DTO
   */
  private ProjectResponse toResponse(Project project, TenantContext tenant) {
    // Tenant boundary defensive check
    if (tenant != null && !project.getOrganisationId().equals(tenant.organisationId())) {
      log.error("TENANT_BOUNDARY_VIOLATION in toResponse projectId={}", project.getId());
      throw new IllegalStateException("Tenant boundary violation");
    }

    // Don't expose organisationId in response - it's implicit from the request context
    return new ProjectResponse(
      project.getId(),
      null, // organisationId not exposed
      project.getName(),
      project.getStatus(),
      Set.copyOf(project.getTeamMemberIds()),
      project.getCreatedAt(),
      project.getUpdatedAt()
    );
  }

  // ==================== Private Logging ====================

  /**
   * Sets tenant context in MDC for structured logging.
   */
  private void setTenantLogContext(TenantContext tenant) {
    MDC.put(LOG_CONTEXT_ORG, tenant.organisationId().toString());
    MDC.put(LOG_CONTEXT_USER, tenant.userId().toString());
  }
}