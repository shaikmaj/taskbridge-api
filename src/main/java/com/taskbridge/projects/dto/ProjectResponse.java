package com.taskbridge.projects.dto;

import com.taskbridge.projects.model.ProjectStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Response DTO for Project.
 * Excludes organisationId as it's implicit from the request context (tenant isolation).
 * Note: teamMemberIds will be filtered based on permissions in future enhancements.
 */
public record ProjectResponse(
  UUID id,
  String name,
  ProjectStatus status,
  Set<UUID> teamMemberIds,
  Instant createdAt,
  Instant updatedAt
) {
  // Legacy constructor that accepts organisationId (for backward compatibility during migration)
  public ProjectResponse(
    UUID id,
    UUID organisationId,
    String name,
    ProjectStatus status,
    Set<UUID> teamMemberIds,
    Instant createdAt,
    Instant updatedAt
  ) {
    this(id, name, status, teamMemberIds, createdAt, updatedAt);
    // organisationId parameter ignored for security
  }
}