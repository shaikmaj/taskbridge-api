package com.taskbridge.projects.dto;

import jakarta.validation.constraints.*;
import java.util.Set;
import java.util.UUID;

/**
 * Request DTO for creating a new project.
 * Enforces comprehensive input validation at the API boundary.
 */
public record CreateProjectRequest(
  @NotBlank(message = "Project name is required")
  @Size(min = 3, max = 120, message = "Project name must be between 3 and 120 characters")
  @Pattern(
    regexp = "^[a-zA-Z0-9\\s\\-_.()]+$",
    message = "Project name contains invalid characters"
  )
  String name,

  @NotEmpty(message = "At least one team member is required")
  @Size(max = 50, message = "Maximum 50 team members per project")
  Set<@NotNull(message = "Team member ID cannot be null") UUID> teamMemberIds
) {}