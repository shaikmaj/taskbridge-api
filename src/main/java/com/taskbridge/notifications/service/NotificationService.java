package com.taskbridge.notifications.service;

import com.taskbridge.common.ForbiddenException;
import com.taskbridge.common.NotFoundException;
import com.taskbridge.common.TenantContext;
import com.taskbridge.notifications.dto.NotificationResponse;
import com.taskbridge.notifications.model.EventType;
import com.taskbridge.notifications.model.Notification;
import com.taskbridge.notifications.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final NotificationRepository repository;

  public NotificationService(NotificationRepository repository) {
    this.repository = repository;
  }

  /** Creates one notification for each valid recipient in the tenant. */
  @Transactional
  public List<NotificationResponse> dispatch(
    Set<UUID> recipients,
    UUID projectId,
    EventType eventType,
    String message,
    UUID organisationId
  ) {
    if (recipients == null || recipients.isEmpty()) {
      throw new IllegalArgumentException("At least one recipient is required");
    }
    if (projectId == null) {
      throw new IllegalArgumentException("Project ID is required");
    }
    if (eventType == null) {
      throw new IllegalArgumentException("Event type is required");
    }
    if (organisationId == null) {
      throw new IllegalArgumentException("Organisation ID is required");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("Notification message is required");
    }
    if (message.length() > 500) {
      throw new IllegalArgumentException("Notification message must be 500 characters or fewer");
    }

    return recipients.stream()
      .map(id -> new Notification(id, organisationId, eventType, projectId, message.trim(), Instant.now()))
      .map(repository::save)
      .map(this::toResponse)
      .toList();
  }

  /** Returns unread notifications only for the authenticated user and tenant. */
  @Transactional(readOnly = true)
  public List<NotificationResponse> unread(UUID requestedUserId, TenantContext tenant) {
    if (!requestedUserId.equals(tenant.userId())) {
      throw new ForbiddenException("Users may only read their own notifications");
    }
    return repository.findAllByRecipientUserIdAndOrganisationIdAndReadFalseOrderByCreatedAtDesc(
        requestedUserId,
        tenant.organisationId()
      )
      .stream()
      .map(this::toResponse)
      .toList();
  }

  /** Marks a tenant-owned notification as read. */
  @Transactional
  public NotificationResponse markRead(UUID id, TenantContext tenant) {
    Notification notification = repository.findByIdAndOrganisationId(id, tenant.organisationId())
      .orElseThrow(() -> new NotFoundException("Notification not found"));

    if (!notification.getRecipientUserId().equals(tenant.userId())) {
      throw new ForbiddenException("Notification belongs to another user");
    }

    notification.markRead();
    Notification saved = repository.save(notification);
    return toResponse(saved);
  }

  private NotificationResponse toResponse(Notification notification) {
    return new NotificationResponse(
      notification.getId(),
      notification.getRecipientUserId(),
      notification.getEventType(),
      notification.getProjectId(),
      notification.getMessage(),
      notification.isRead(),
      notification.getCreatedAt()
    );
  }
}
