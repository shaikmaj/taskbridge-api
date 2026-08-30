# Impact Analysis

## Change
Add `MILESTONE_REOPENED` and capture actor IP address before implementation.

## Impacted areas
* `EventType`: additive enum value; consumers with exhaustive switches may require updates.
* `ProjectService`: additive transition mapping from CLOSED to non-CLOSED.
* `AuditEntry`: additive non-null `actorIpAddress`; database migration and backfill/default strategy required.
* `CreateAuditRequest` and `AuditResponse`: response contract is additive; actor IP is taken from trusted context, not the body.
* `TenantContextResolver`: captures the first trusted proxy-injected address; deployment must configure trusted proxies.
* Audit tests and notification tests: add reopen and IP coverage.
* Documentation/OpenAPI: describe the new event and personal-data handling.

## Risks
An IP address can be personal data. Risks include excessive retention, unnecessary exposure through APIs/logs, spoofed forwarding headers, and wider incident impact. Restrict access, avoid operational logging, encrypt storage/backups, define retention/deletion policy subject to legal requirements, and trust forwarding headers only from configured proxies.

## Sequence
1. Approve privacy/retention and access controls. 2. Add nullable column. 3. Deploy code that writes IP. 4. Backfill or document legacy nulls. 5. Enforce desired constraint. 6. Add enum and transition logic. 7. Update tests/contracts and monitor.

## How Copilot Assisted This Analysis
Prompt: "Act as a senior Spring architect and privacy reviewer. List code, schema, API, test and operational impacts of adding MILESTONE_REOPENED and actor IP." Copilot identified model and test changes. Human review added proxy trust, retention, logging exposure, migration sequencing and contract compatibility.
