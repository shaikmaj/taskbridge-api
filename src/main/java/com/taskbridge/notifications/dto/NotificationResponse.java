package com.taskbridge.notifications.dto;
import com.taskbridge.notifications.model.EventType;
import java.time.Instant;
import java.util.UUID;
public record NotificationResponse(UUID id,UUID recipientUserId,EventType eventType,UUID projectId,String message,boolean read,Instant createdAt) {}