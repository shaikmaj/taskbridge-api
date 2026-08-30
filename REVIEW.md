# Project Service Review

## Findings
1. **Critical, service/repository:** no tenant key in reads or writes could expose another organisation's projects. Fixed with `findByIdAndOrganisationId` and tenant-derived ownership.
2. **High, persistence:** direct database access would encourage injection and inconsistent mapping. Replaced with Spring Data JPA.
3. **High, API:** entities accepted and returned directly could permit mass assignment and data exposure. Replaced with validated request and response records.
4. **High, lifecycle:** project changes had no audit/notification orchestration. Added transactional calls for create, status update, reopen, close and delete.
5. **Medium, errors:** generic failures produced unstable responses. Added typed exceptions and a global error contract.
6. **Medium, observability:** no structured logs. Added event-only logs without state snapshots or IP addresses.

## Review process
Copilot Chat was prompted to identify architecture, OWASP-style risks and test gaps. Findings were verified manually by tracing every controller-to-repository path and checking whether organisationId was mandatory. Human review rejected header identity as a production authentication mechanism and documented the gateway/JWT requirement.

## Architectural & Security Issues Copilot Introduced That Required Human Judgment
The rushed generation omitted tenant isolation, immutable audit boundaries, transaction ownership, DTO separation and privacy treatment for IP addresses. These omissions are especially risky because downstream services would treat Project Service output as trusted and could amplify cross-tenant leakage. The remediation uses tenant-scoped repository methods, stable contracts, append-only audit methods, validated DTOs and explicit privacy controls.
