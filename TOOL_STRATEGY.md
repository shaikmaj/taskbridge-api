# Copilot Tool Strategy

## Feature Usage Log
1. Chat: translated product brief into SPEC.md; best for multi-turn design reasoning.
2. Inline Chat: generated one DTO/entity at a time; best for local context and small diffs.
3. Agent mode: applied package-wide implementation and ran tests; best for coordinated file changes.
4. Code Review: checked tenant isolation and privacy risks; best for diff-oriented feedback.
5. Chat with `@workspace`: traced Project Service dependencies; best for codebase-wide understanding.
6. Copilot instructions: persisted Java, security and testing constraints across sessions.

## Scenario Responses
* **Understand a 600-line legacy service:** Copilot Chat with `@workspace`, requesting call-flow, dependencies and risks with file/line references. It can use repository context while keeping the investigation conversational.
* **Consistent validation across 10 routes:** Agent mode guided by copilot-instructions.md. It can make coordinated edits, while tests and review validate consistency.
* **Verify JWT expiry/tampering:** Copilot Chat plus targeted unit-test generation in Inline Chat. Ask for adversarial tests, then verify against the selected JWT library's official behaviour.
* **Enforce lint/coverage on main:** Agent mode to create a GitHub Actions workflow and branch-protection checklist. CI, not Copilot itself, performs the automatic enforcement.
* **Review contractor service:** Copilot Code Review with explicit OWASP and multi-tenant criteria, followed by human threat modelling.
* **Consistent tenant rules:** repository-level `.github/copilot-instructions.md`, reinforced with tests and code review. Instructions provide persistent context, while tests remain the enforcement mechanism.

## Limitations Encountered
1. Prompted for CRUD repositories; Copilot proposed `findById(id)` without organisationId. Manual data-flow review exposed cross-tenant risk; fixed with composite filters. Next time place tenant invariants in the first prompt.
2. Prompted for immutable audit; Copilot still generated setters/delete. API inspection caught this; fixed with no setters, `@Immutable`, no endpoint and rejection tests. Next time include forbidden operations as negative constraints.
3. Prompted to capture IP; Copilot trusted `X-Forwarded-For` and logged it. Threat modelling caught spoofing/privacy issues; removed logging and documented trusted proxies/retention. Next time request a privacy and deployment threat model before code.
