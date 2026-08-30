# Prompt Engineering Log

## Prompt chain
1. **Chat, role-based + constraint:** "Act as a senior Java architect. Draft a 1–2 page specification for a tenant-isolated Notification and Audit Service. Use Java 21/Spring Boot, append-only audit, typed DTOs and the four required endpoints. Mark assumptions." Rationale: establish contracts before code.
2. **Inline Chat, decomposition:** "Create only the AuditEntry JPA entity and repository. Do not expose update/delete operations; include organisationId in queries." Rationale: constrain one persistence concern.
3. **Chat, specificity:** "Generate request/response records with Bean Validation for POST /audit and project status updates. Actor and organisation must not come from request bodies." Rationale: prevent mass assignment.
4. **Agent mode, iterative refinement:** "Implement controllers and services against SPEC.md and copilot-instructions.md, then run tests. Fix compilation only; do not weaken tenant checks." Rationale: repository-wide consistency.
5. **Code Review, security role:** "Review every repository call for tenant isolation, audit immutability, state exposure, IP logging and transaction safety." Rationale: identify cross-cutting risks.
6. **Inline Chat, few-shot + constraint:** "Add tests in Arrange/Act/Assert style. Example: verify repository.search(projectId, tenantOrg, from, to, type). Cover the six named scenarios without network or database dependencies." Rationale: deterministic tests.

## Post-Generation Corrections
* Added organisationId to all repository keys after generated `findById` leaked scope.
* Removed actor/organisation from request bodies and derived them from authenticated context.
* Replaced mutable audit setters and delete endpoint with an immutable entity and explicit rejection methods.
* Replaced raw entity responses with DTOs.
* Added CLOSED-to-active mapping for `MILESTONE_REOPENED`.
* Stopped logging snapshots/IP values.
* Added date-range validation and stable exception responses.
* Documented trusted-proxy requirements and IP retention risks.
