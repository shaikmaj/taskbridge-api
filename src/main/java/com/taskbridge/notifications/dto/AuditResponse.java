package com.taskbridge.notifications.dto;
import com.taskbridge.notifications.model.EventType;
import java.time.Instant;
import java.util.UUID;
public record AuditResponse(UUID id,EventType eventType,String entityType,UUID entityId,UUID projectId,UUID actorUserId,UUID organisationId,String actorIpAddress,String previousState,String newState,Instant timestamp) {}