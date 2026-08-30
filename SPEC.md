# Notification & Audit Service Specification

## Purpose
Record immutable project milestone changes and notify all project team members. Every operation is tenant-scoped.

## Models
`AuditEntry`: UUID id, EventType eventType, String entityType, UUID entityId, UUID projectId, UUID actorUserId, UUID organisationId, String actorIpAddress, JSON previousState, JSON newState, Instant timestamp. Entries are append-only.

`Notification`: UUID id, UUID recipientUserId, UUID organisationId, EventType eventType, UUID projectId, String message, boolean read, Instant createdAt.

## Contracts
* `POST /audit`: accepts eventType, entityType, entityId, projectId, previousState, newState; actor and tenant come from authenticated context; returns 201 and the audit record.
* `GET /audit/{projectId}?from=&to=&eventType=`: returns newest-first tenant-scoped history; dates are ISO-8601 and inclusive.
* `GET /notifications/{userId}`: returns unread notifications; caller must match userId.
* `PATCH /notifications/{id}/read`: marks the caller's notification as read.
* Project endpoints create/update/delete projects and synchronously invoke audit plus notification services in the same transaction.

## Constraints
Tenant context never comes from a request body. Project and audit lookups include organisationId. Audit update/delete is not exposed. Input is validated. IP addresses require restricted access and retention controls.

## Copilot and human judgement
Copilot assisted with DTO, repository and controller scaffolding. Human judgement added tenant-scoped keys, append-only boundaries, DTO/entity separation, IP privacy notes, error contracts and transactional consistency.
