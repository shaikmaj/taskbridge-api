package com.taskbridge.notifications.model;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
@Entity @Table(name="notifications",indexes=@Index(name="idx_notification_recipient",columnList="organisation_id,recipient_user_id,is_read"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Notification {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false,name="recipient_user_id") private UUID recipientUserId;
 @Column(nullable=false,name="organisation_id") private UUID organisationId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=40) private EventType eventType;
 @Column(nullable=false) private UUID projectId;
 @Column(nullable=false,length=500) private String message;
 @Column(nullable=false,name="is_read") private boolean read;
 @Column(nullable=false,updatable=false) private Instant createdAt;
 public Notification(UUID recipientUserId,UUID organisationId,EventType eventType,UUID projectId,String message,Instant createdAt){ this.recipientUserId=recipientUserId;this.organisationId=organisationId;this.eventType=eventType;this.projectId=projectId;this.message=message;this.createdAt=createdAt; }
 public void markRead(){ this.read=true; }
}