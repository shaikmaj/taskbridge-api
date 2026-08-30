package com.taskbridge.notifications.dto;
import com.taskbridge.notifications.model.EventType;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record CreateAuditRequest(@NotNull EventType eventType,@NotBlank @Size(max=60) String entityType,@NotNull UUID entityId,@NotNull UUID projectId,String previousState,@NotBlank String newState) {}