package com.taskbridge.notifications.repository;
import com.taskbridge.notifications.model.Notification;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NotificationRepository extends JpaRepository<Notification,UUID> {
 List<Notification> findAllByRecipientUserIdAndOrganisationIdAndReadFalseOrderByCreatedAtDesc(UUID userId,UUID organisationId);
 Optional<Notification> findByIdAndOrganisationId(UUID id,UUID organisationId);
}