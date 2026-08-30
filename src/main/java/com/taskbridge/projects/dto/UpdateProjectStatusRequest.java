package com.taskbridge.projects.dto;

import com.taskbridge.projects.model.ProjectStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for updating project status.
 * Validates that status transitions are present.
 */
public record UpdateProjectStatusRequest(
  @NotNull(message = "Project status is required")
  ProjectStatus status
) {}