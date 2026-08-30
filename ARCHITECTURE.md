# Architecture
1. Project Service owns project lifecycle and tenant-scoped project data.
2. Notification & Audit Service owns append-only history and user notifications.
3. Controllers validate transport input and resolve authenticated tenant context.
4. Services enforce authorisation, state transitions and orchestration.
5. Repositories use JPA and organisation-scoped queries.
6. A project mutation captures before/after DTO snapshots.
7. The mutation appends an audit entry and fans notifications to unique team members.
8. All three writes currently share one local transaction.
9. Audit history supports project, inclusive date range and event type filters.
10. Audit entities expose no mutation methods or delete endpoint.
11. DTOs prevent entity over-posting and stabilise contracts.
12. Tenant identity is never accepted from request bodies.
13. This suits B2B SaaS because ownership filtering is mandatory at persistence boundaries.
14. Synchronous consistency is simple but increases coupling.
15. A transactional outbox is the preferred evolution when services deploy independently.
