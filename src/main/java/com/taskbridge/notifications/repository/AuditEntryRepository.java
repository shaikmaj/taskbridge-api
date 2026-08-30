package com.taskbridge.notifications.repository;
import com.taskbridge.notifications.model.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface AuditEntryRepository extends JpaRepository<AuditEntry,UUID> {
 @Query("select a from AuditEntry a where a.projectId=:projectId and a.organisationId=:org and (:from is null or a.timestamp>=:from) and (:to is null or a.timestamp<=:to) and (:eventType is null or a.eventType=:eventType) order by a.timestamp desc")
 List<AuditEntry> search(@Param("projectId")UUID projectId,@Param("org")UUID organisationId,@Param("from")Instant from,@Param("to")Instant to,@Param("eventType")EventType eventType);
}