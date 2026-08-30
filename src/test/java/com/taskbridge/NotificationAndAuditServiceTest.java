package com.taskbridge;

import static org.junit.jupiter.api.Assertions.*;

import com.taskbridge.common.ForbiddenException;
import com.taskbridge.common.NotFoundException;
import com.taskbridge.common.TenantContext;
import com.taskbridge.notifications.dto.NotificationResponse;
import com.taskbridge.notifications.model.EventType;
import com.taskbridge.notifications.model.Notification;
import com.taskbridge.notifications.repository.NotificationRepository;
import com.taskbridge.notifications.service.NotificationService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(NotificationService.class)
class NotificationAndAuditServiceTest {

  @Autowired
  private NotificationRepository notificationRepository;

  @Autowired
  private NotificationService notificationService;

  @Test
  void dispatch_createsOneNotificationPerRecipient() {
    UUID organisationId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    Set<UUID> recipients = Set.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    List<NotificationResponse> result = notificationService.dispatch(
      recipients,
      projectId,
      EventType.MILESTONE_UPDATED,
      "Project updated",
      organisationId
    );

    assertEquals(3, result.size());
    assertEquals(recipients, result.stream().map(NotificationResponse::recipientUserId).collect(java.util.stream.Collectors.toSet()));
    assertEquals(3, notificationRepository.count());
  }

  @Test
  void dispatch_rejectsBlankMessage() {
    UUID organisationId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();

    IllegalArgumentException ex = assertThrows(
      IllegalArgumentException.class,
      () -> notificationService.dispatch(Set.of(recipient), projectId, EventType.MILESTONE_UPDATED, "   ", organisationId)
    );

    assertTrue(ex.getMessage().contains("message"));
  }

  @Test
  void unread_returnsOnlyCurrentUsersUnreadNotifications() {
    UUID currentUser = UUID.randomUUID();
    UUID otherUser = UUID.randomUUID();
    UUID organisationId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();

    notificationRepository.save(new Notification(currentUser, organisationId, EventType.MILESTONE_CREATED, projectId, "own unread", Instant.now()));
    notificationRepository.save(new Notification(otherUser, organisationId, EventType.MILESTONE_UPDATED, projectId, "other unread", Instant.now()));
    notificationRepository.save(new Notification(currentUser, organisationId, EventType.MILESTONE_CLOSED, projectId, "already read", Instant.now()));
    notificationRepository.save(new Notification(currentUser, UUID.randomUUID(), EventType.MILESTONE_REOPENED, projectId, "wrong org", Instant.now()));

    notificationRepository.findAll().stream()
      .filter(n -> n.getMessage().equals("already read"))
      .forEach(n -> n.markRead());
    notificationRepository.saveAll(notificationRepository.findAll().stream()
      .filter(n -> n.getMessage().equals("already read"))
      .toList());

    List<NotificationResponse> unread = notificationService.unread(
      currentUser,
      new TenantContext(currentUser, organisationId, "127.0.0.1")
    );

    assertEquals(1, unread.size());
    assertEquals(currentUser, unread.get(0).recipientUserId());
    assertEquals("own unread", unread.get(0).message());
  }

  @Test
  void markRead_marksOwnedNotificationAsRead() {
    UUID userId = UUID.randomUUID();
    UUID organisationId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();

    Notification notification = notificationRepository.save(
      new Notification(userId, organisationId, EventType.MILESTONE_CREATED, projectId, "hello", Instant.now())
    );

    NotificationResponse response = notificationService.markRead(
      notification.getId(),
      new TenantContext(userId, organisationId, "127.0.0.1")
    );

    assertTrue(response.read());
    assertEquals("hello", response.message());
    Notification persisted = notificationRepository.findById(notification.getId()).orElseThrow();
    assertTrue(persisted.isRead());
  }

  @Test
  void markRead_rejectsNotificationsOwnedByAnotherUser() {
    UUID owner = UUID.randomUUID();
    UUID attacker = UUID.randomUUID();
    UUID organisationId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();

    Notification notification = notificationRepository.save(
      new Notification(owner, organisationId, EventType.MILESTONE_UPDATED, projectId, "private", Instant.now())
    );

    ForbiddenException ex = assertThrows(
      ForbiddenException.class,
      () -> notificationService.markRead(notification.getId(), new TenantContext(attacker, organisationId, "127.0.0.1"))
    );

    assertTrue(ex.getMessage().contains("another user"));
  }

  @Test
  void markRead_throwsNotFoundForMissingTenantNotification() {
    UUID userId = UUID.randomUUID();
    UUID organisationId = UUID.randomUUID();

    NotFoundException ex = assertThrows(
      NotFoundException.class,
      () -> notificationService.markRead(UUID.randomUUID(), new TenantContext(userId, organisationId, "127.0.0.1"))
    );

    assertTrue(ex.getMessage().contains("Notification not found"));
  }
}
