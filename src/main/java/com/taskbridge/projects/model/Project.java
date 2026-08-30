package com.taskbridge.projects.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import lombok.*;

@Entity
@Table(
  name = "projects",
  indexes = {
    @Index(name = "idx_project_org", columnList = "organisation_id"),
    @Index(name = "idx_project_deleted", columnList = "deleted")
  }
)
@Getter
@Setter
@NoArgsConstructor
public class Project {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, name = "organisation_id", updatable = false)
  private UUID organisationId;

  @Column(nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ProjectStatus status;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
    name = "project_team_members",
    joinColumns = @JoinColumn(name = "project_id")
  )
  @Column(name = "user_id", nullable = false)
  private Set<UUID> teamMemberIds = new HashSet<>();

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private Boolean deleted = false;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @PrePersist
  void prePersist() {
    var now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public void softDelete() {
    this.deleted = true;
    this.deletedAt = Instant.now();
  }

  public boolean isDeleted() {
    return deleted != null && deleted;
  }
}