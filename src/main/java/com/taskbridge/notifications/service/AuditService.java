package com.taskbridge.notifications.service;
import com.taskbridge.common.*;
import com.taskbridge.notifications.dto.*;
import com.taskbridge.notifications.model.*;
import com.taskbridge.notifications.repository.AuditEntryRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AuditService {
 private final AuditEntryRepository repository;
 public AuditService(AuditEntryRepository repository){ this.repository=repository; }
 /** Records one append-only audit event. No update or delete operation is exposed. */
 @Transactional public AuditResponse record(CreateAuditRequest request,TenantContext tenant){
  AuditEntry saved=repository.save(new AuditEntry(request.eventType(),request.entityType(),request.entityId(),request.projectId(),tenant.userId(),tenant.organisationId(),tenant.ipAddress(),request.previousState(),request.newState(),Instant.now()));
  return toResponse(saved);
 }
 /** Returns tenant-scoped project history with optional inclusive filters. */
 @Transactional(readOnly=true) public List<AuditResponse> history(UUID projectId,Instant from,Instant to,EventType type,TenantContext tenant){
  if(from!=null&&to!=null&&from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
  return repository.search(projectId,tenant.organisationId(),from,to,type).stream().map(this::toResponse).toList();
 }
 /** Audit deletion is prohibited by design. */
 public void delete(UUID ignored){ throw new ConflictException("Audit entries are immutable and cannot be deleted"); }
 /** Audit replacement is prohibited by design. */
 public void overwrite(UUID ignored,CreateAuditRequest request){ throw new ConflictException("Audit entries are immutable and cannot be overwritten"); }
 private AuditResponse toResponse(AuditEntry a){ return new AuditResponse(a.getId(),a.getEventType(),a.getEntityType(),a.getEntityId(),a.getProjectId(),a.getActorUserId(),a.getOrganisationId(),a.getActorIpAddress(),a.getPreviousState(),a.getNewState(),a.getTimestamp()); }
}