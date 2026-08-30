package com.taskbridge.projects.controller;

import com.taskbridge.common.*;
import com.taskbridge.projects.dto.*;
import com.taskbridge.projects.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Project endpoints.
 * Handles HTTP request/response mapping and delegates business logic to service layer.
 * All endpoints require tenant authentication headers (X-User-Id, X-Organisation-Id).
 */
@RestController
@RequestMapping("/projects")
public class ProjectController {
  private final ProjectService service;
  private final TenantContextResolver resolver;

  public ProjectController(ProjectService service, TenantContextResolver resolver) {
    this.service = service;
    this.resolver = resolver;
  }

  /**
   * POST /projects
   * Creates a new project.
   *
   * @param body Request with project name and team members
   * @param request HTTP request (contains tenant headers)
   * @return Created project with 201 status
   */
  @PostMapping
  ResponseEntity<ProjectResponse> create(
    @Valid @RequestBody CreateProjectRequest body,
    HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
      .body(service.create(body, resolver.resolve(request)));
  }

  /**
   * GET /projects/{id}
   * Retrieves a single project.
   *
   * @param id Project ID
   * @param request HTTP request (contains tenant headers)
   * @return Project details
   */
  @GetMapping("/{id}")
  ProjectResponse get(@PathVariable UUID id, HttpServletRequest request) {
    return service.get(id, resolver.resolve(request));
  }

  /**
   * GET /projects?teamMemberId={id}
   * Retrieves all projects for a specific team member.
   * Authorization: User can only query their own projects (unless admin).
   *
   * @param teamMemberId Team member UUID
   * @param request HTTP request (contains tenant headers)
   * @return List of projects for the team member
   */
  @GetMapping
  List<ProjectResponse> byTeam(
    @RequestParam UUID teamMemberId,
    HttpServletRequest request
  ) {
    return service.getByTeam(teamMemberId, resolver.resolve(request));
  }

  /**
   * PATCH /projects/{id}/status
   * Updates project status.
   *
   * @param id Project ID
   * @param body Request with new status
   * @param request HTTP request (contains tenant headers)
   * @return Updated project
   */
  @PatchMapping("/{id}/status")
  ProjectResponse update(
    @PathVariable UUID id,
    @Valid @RequestBody UpdateProjectStatusRequest body,
    HttpServletRequest request
  ) {
    return service.updateStatus(id, body, resolver.resolve(request));
  }

  /**
   * DELETE /projects/{id}
   * Soft-deletes a project.
   *
   * @param id Project ID
   * @param request HTTP request (contains tenant headers)
   * @return 204 No Content on success
   */
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
    service.delete(id, resolver.resolve(request));
    return ResponseEntity.noContent().build();
  }
}