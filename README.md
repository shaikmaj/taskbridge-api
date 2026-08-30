# TaskBridge API
Production-style reference implementation for the GitHub Copilot practitioner assessment.

## Stack
Java 21, Spring Boot 3.3, Spring Data JPA, PostgreSQL, Maven, JUnit 5, Mockito, OpenAPI.

## Run
1. Create PostgreSQL database `taskbridge`.
2. Set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`.
3. Run `mvn spring-boot:run`.
4. Swagger UI is available at `/swagger-ui.html`.

All endpoints require `X-User-Id` and `X-Organisation-Id`. In production, a verified JWT/gateway should supply these values and overwrite untrusted inbound headers.

## Tests
Run `mvn test`.
