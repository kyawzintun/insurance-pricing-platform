# Phase 4 Validation

Status: Complete, validated on 2026-10-03. Phase 5 — API Gateway and JWT Validation has not started.

## Implementation Scope

Only Auth Service runtime code/configuration and its dependency list changed. Added the User entity/repository, CUSTOMER/ADMIN and ACTIVE/DISABLED enums, registration/login DTOs and controller/service, consistent errors, BCrypt security configuration, and JWT generation. Replaced Auth's temporary Phase 2 security chain. Added the Boot-managed `spring-security-oauth2-jose` dependency; no independent library version override.

Existing Maven baseline, other service implementations, gateway, Docker infrastructure, and all Flyway migrations remain unchanged. `.env.example` and project documentation now describe the required signing key and current scope.

## Automated Validation

Final command: `./mvnw -B clean verify` — all six modules passed, **24 tests**, zero failures/errors (19 Auth tests and five existing other-service startup tests).

Auth's 16 HTTP/context test cases exercise the actual random-port server, security filter chain, Bean Validation, request binding, exception handling, service, real BCrypt cost 12, and JWT encoding with a mocked UserRepository. Three JWT/helper tests cover configurable expiry, ADMIN claims, signature tampering, invalid signing secrets, and sensitive DTO string redaction. An ephemeral signing key is generated in memory for tests; no real key is committed. Tests do not require PostgreSQL or Kafka.

Covered behavior:

- Successful registration with canonical email, trimmed names, safe response, ACTIVE CUSTOMER role/status, and salted BCrypt hash.
- Existing and database-constraint duplicate email errors (409).
- Rejection of client-supplied role/status, blank fields, invalid email, too-short passwords, and passwords exceeding 72 UTF-8 bytes, including multibyte input.
- Successful login and verified HS256 signature, subject, role, issuer, issued-at/expiry timestamps, default 900-second lifetime, and no-store response.
- Identical 401 bodies for unknown accounts, wrong passwords, and disabled users.
- Health/info remain public; other paths and wrong HTTP methods are protected.
- Malformed JSON and unsupported content types return safe 400 errors.
- Custom 300-second lifetime, ADMIN role, tamper rejection, positive-TTL validation, and invalid key rejection.

An early JWT assertion used `getIssuer()`, which expects a URL; the configured issuer is a valid string identifier. The assertion was corrected to read the `iss` string claim. The original skeleton context test was expanded with a mocked repository and generated key to support the new beans. No schema change was necessary.

## Live PostgreSQL / HTTP Validation

Started the packaged Auth application with the local profile, existing exported datasource overrides, an ephemeral random signing key, `AUTH_SERVICE_PORT=18081`, and `AUTH_JWT_ACCESS_TOKEN_TTL=5m`. The normal application port remains 8081. Existing infrastructure stayed running.

A temporary local validation script performed these checks without printing passwords, hashes, or tokens:

| Check | Result |
| --- | --- |
| `/actuator/health` | 200 / UP; existing Flyway history successfully validated. |
| Register mixed-case, whitespace-padded email | 201; canonical persisted email, trimmed names, ACTIVE CUSTOMER. |
| Stored password | BCrypt `$2a$12$` hash; never plaintext; safe API response. |
| Duplicate canonical email | 409. |
| Supplied ADMIN role or DISABLED status | 400. |
| Valid login | 200; token signature independently verified with HMAC-SHA256. |
| JWT claims | Correct user UUID/role, exactly sub/roles/iat/exp/iss, 300-second configured lifetime. |
| Wrong password, unknown email, disabled account | Identical 401 INVALID_CREDENTIALS responses. |
| Explicit manual database promotion of synthetic account | Subsequent login issued ADMIN role; validates documented local provisioning approach. |
| Two simultaneous registrations for the same new email | Exactly one 201, one 409, and one persisted row. |
| Protected path | 401. |
| Flyway | Version/checksum/success records unchanged; no new migration or schema changes. |
| Refresh-token / outbox tables | Row counts unchanged; no related behavior implemented. |
| Kafka | Domain topic metadata, partition offsets, and consumer-group list unchanged. No Auth Kafka dependency/code. |
| Application log inspection | Generated password, stored hashes, and issued tokens absent. |

The application was stopped and only this run's two synthetic accounts were deleted. No existing accounts were modified or removed. Generated secrets remained in process memory/environment, not repository files. Shared infrastructure remains available.

## Remaining Boundaries

- Email uniqueness is enforced through canonical application writes and the existing unique constraint; direct SQL writes must preserve canonical form.
- BCrypt passwords are not trimmed or silently truncated. Public registration cannot assign roles/status.
- The development ADMIN flow is explicit local SQL promotion after ordinary registration, with no public endpoint or startup credentials.
- JWT signing is symmetric and issuance-only. Bearer validation, gateway routes, and distributed authorization are deferred to Phase 5. Other Auth paths currently reject unauthenticated requests; issuing a JWT does not yet make it usable through Gateway.
- Disabling an account blocks new login but does not revoke existing access tokens. Refresh tokens, logout, rotation, revocation, production provisioning, and rate limiting are not implemented.
- No other-service business logic, Angular, Kafka producers/consumers, outbox publisher, migrations, or schema changes were added.
- Tests use a mocked repository; real persistence and duplicate-race behavior were verified separately as above, not through a permanent Testcontainers suite.

Phase 4 is ready for Phase 5. Changes have not been committed.

## Local Startup Simplification — 2026-10-04

At the user's request, Auth now defaults to the local profile with PostgreSQL port 15432 and an explicitly public development-only signing key in local YAML. The earlier required-key setup applies outside the local profile. Environment overrides remain supported. The optional launcher was removed, and startup instructions now use `./mvnw -pl services/auth-service spring-boot:run` directly. Auth's Maven verification passed after this configuration change.
