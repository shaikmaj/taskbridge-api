package com.taskbridge.notifications.model;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;
@Entity @Immutable @Table(name="audit_entries",indexes={@Index(name="idx_audit_project_time",columnList="project_id,timestamp"),@Index(name="idx_audit_org",columnList="organisation_id")})
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class AuditEntry {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=40) private EventType eventType;
 @Column(nullable=false,length=60) private String entityType;
 @Column(nullable=false) private UUID entityId;
 @Column(nullable=false,name="project_id") private UUID projectId;
 @Column(nullable=false) private UUID actorUserId;
 @Column(nullable=false,name="organisation_id") private UUID organisationId;
 @Column(nullable=false,length=45) private String actorIpAddress;
 @Lob @Column(columnDefinition="TEXT") private String previousState;
 @Lob @Column(columnDefinition="TEXT",nullable=false) private String newState;
 @Column(nullable=false,updatable=false) private Instant timestamp;
 public AuditEntry(EventType eventType,String entityType,UUID entityId,UUID projectId,UUID actorUserId,UUID organisationId,String actorIpAddress,String previousState,String newState,Instant timestamp){
  this.eventType=eventType; this.entityType=entityType; this.entityId=entityId; this.projectId=projectId; this.actorUserId=actorUserId; this.organisationId=organisationId; this.actorIpAddress=actorIpAddress; this.previousState=previousState; this.newState=newState; this.timestamp=timestamp;
 }
}