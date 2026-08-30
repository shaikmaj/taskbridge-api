# PR: Add tenant-safe Notification & Audit capability

## Summary
Remediates the inherited Project Service and adds append-only audit history plus unread team notifications for milestone events.

## AI Tool Disclosure
Used Copilot Chat for design/review, Inline Chat for bounded code generation, Agent mode for repository-wide implementation, and Copilot Code Review for security checks. AI output was accepted for routine DTO/controller scaffolding and overridden for tenant isolation, audit immutability, proxy trust and privacy. Estimate: 55% AI-assisted generation, 45% human-written or materially corrected.

## Integration
Project Service calls AuditService and NotificationService synchronously in the same Spring transaction. The internal audit contract is `CreateAuditRequest`; identity comes from trusted tenant context.

## Tests / gaps
Six required unit tests cover fan-out, creation, immutability, date/type filters and tenant separation. Gaps: no Testcontainers PostgreSQL integration test, concurrency test, gateway/JWT integration, or delivery-channel retry because those are beyond this bounded reference implementation.

## Risk / trade-off
Synchronous integration gives transactional consistency but couples project availability to audit/notification persistence. An outbox/event-driven design would improve resilience at the cost of eventual consistency and operational complexity.

## Self-review
- [x] Tenant key on every ownership query
- [x] No audit update/delete API
- [x] DTO validation enabled
- [x] No IP/snapshot logging
- [x] Six required tests included
- [x] Secrets externalised

## Peer Review Simulation
1. `TenantContextResolver#clientIp`: trust `X-Forwarded-For` only when the request comes from an approved proxy; otherwise clients can spoof the audit IP.
2. `ProjectService#emit`: consider a transactional outbox before independent deployment, because a remote audit call inside the project transaction can create partial failures.
3. `AuditController#history`: add pagination and a maximum date window to prevent unbounded compliance queries from exhausting memory.
