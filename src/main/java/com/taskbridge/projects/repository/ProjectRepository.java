package com.taskbridge.projects.repository;

import com.taskbridge.projects.model.Project;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository layer for Project entity.
 * All queries exclude soft-deleted records by default.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {
  /**
   * Finds a project by ID and organisation ID, excluding soft-deleted records.
   */
  Optional<Project> findByIdAndOrganisationIdAndDeletedFalse(
    UUID id,
    UUID organisationId
  );

  /**
   * Finds all active projects for a team member within an organisation.
   */
  List<Project> findByOrganisationIdAndTeamMemberIdsContainingAndDeletedFalse(
    UUID organisationId,
    UUID teamMemberId
  );

  /**
   * Finds all active projects for an organisation.
   */
  List<Project> findByOrganisationIdAndDeletedFalse(UUID organisationId);

  /**
   * Custom query to check if project exists and is not deleted.
   */
  @Query(
    "SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END "
    + "FROM Project p WHERE p.id = :id AND p.organisationId = :organisationId "
    + "AND p.deleted = false"
  )
  boolean existsActiveProject(
    @Param("id") UUID id,
    @Param("organisationId") UUID organisationId
  );
}