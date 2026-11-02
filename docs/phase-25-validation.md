# Phase 25 validation — 23 October 2026

Phase 25 — Service-to-Service Authentication is complete for the learning platform
and ready for Phase 26 planning. Phase 26 has not started. Production hardening
remains deferred as described below.

## Executed checks

| Command/check | Result |
| --- | --- |
| `./mvnw verify -Ppostgres-it -q` | PASS: all six modules build/package, unit/HTTP security tests and disposable PostgreSQL integration tests |
| `./mvnw -pl services/auth-service,services/quote-service test -q` | PASS after final log-redaction/correlation/successful retry tests |
| Final Surefire XML totals | 444 tests, zero failures/errors/skips |
| Failsafe PostgreSQL XML totals | 58 tests, zero failures/errors/skips |
| `python3 infrastructure/validation/phase25-service-auth-e2e.py` | PASS: disposable PostgreSQL/Kafka; real Auth, Quote, Pricing, Gateway and Audit |
| `git diff --check` | PASS |

Maven required execution outside the sandbox to bind localhost HTTP servers. The
initial restricted attempt failed with `SocketException: Operation not permitted`;
the authorized reruns passed. An initial E2E assertion expected array-only audience
serialization; it was corrected to accept standard single-string audience encoding
as well. Application audience validation uses Spring's normalized audience list.

PostgreSQL integration counts: Auth 10, Quote 20, Pricing 4, Notification 11, Audit 13.
They exercise existing registration/refresh, aggregate/outbox rollback, pricing
administration, idempotency and consumer persistence contracts. No migrations changed.
Kafka-specific Maven profiles were not rerun; the E2E used disposable Kafka and the
normal outbox publishers. Existing retry/DLQ code was unchanged.

## Auth evidence

A real HTTP Basic/form exchange with the configured test secret returns a service
access token, no Set-Cookie, and no refresh-token/database/outbox interactions.
The test cryptographically verifies the token using the public key with RS256 pinned;
it asserts subject, issuer infrastructure, service use, scope, audience, absent roles
and secrets, and the configured 45-second issued-at/expiry interval. Invalid client,
secret, malformed/missing credentials yield identical safe 401; unsupported grant is
400. Captured application output excludes secret, raw Basic credential and JWT.
Existing user registration/login/refresh/logout tests pass without changing semantics.
The real-process E2E also verifies an externally keyed eight-second service token.

## Quote evidence

A controlled ten-thread empty-cache test observes exactly one Auth acquisition and
all callers reuse the token. The near-expiry test replaces it, failed acquisition
returns a safe Quote error, and restoring Auth permits recovery. Pricing client tests
assert Bearer authentication and the exact incoming correlation ID, two attempts for
repeated 401, one attempt for 403, and successful calculation after the first 401.
HTTP quote tests assert token failure never calls Pricing or persistence/outbox/event
creation. Existing Pricing business/malformed-response/timeouts and quote transaction
safety tests continue passing. Tokens remain only in instance memory.

## Pricing authorization proof

| Credential/request | Result |
| --- | --- |
| Valid quote-service service JWT + scope + audience | 200, calculation succeeds |
| Missing credential | 401 |
| Expired JWT | 401 |
| Wrong RSA, HS256/public-key confusion, none, RS512, malformed | 401 in security tests |
| CUSTOMER user JWT -> internal calculate | 403 |
| ADMIN user JWT -> internal calculate | 403 |
| Wrong service subject | 403 |
| Missing required scope | 403 |
| Wrong audience | 403 |
| Service JWT -> Pricing admin | 403 |
| ADMIN user JWT -> Pricing admin | 200 (existing tests and real E2E) |
| CUSTOMER user JWT -> Quote customer API | allowed (real Gateway flow) |

Pricing verifies signature/issuer/expiry independently with the RSA public key;
no remote Auth lookup is involved. Admin authorization does not map service scopes
or service roles into user roles. Gateway has no internal Pricing route.

## Disposable E2E and data safety

The harness creates unique containers, databases, topics, ports, external RSA keys and
credentials; it does not touch the existing Compose containers/volumes. It cleans its
containers/processes and ephemeral private keys in `finally`.

Safe captured results:

- CUSTOMER authenticated: yes; login/refresh RSA verification and cookie rotation/replay passed.
- Quote service token obtained: yes; identity quote-service, required scope and audience verified.
- Pricing authenticated service: yes; direct protected calculation passed.
- Quote created through Gateway: yes; three sequential quotes persisted and QuotePriced intents existed.
- Cached token reused: yes; stopping Auth immediately still permitted another quote.
- Expired cache with Auth unavailable: safe 503, unchanged quote and outbox row counts.
- Auth restored: subsequent quote succeeded.
- Wrong configured secret after Quote restart: safe 503, unchanged quote count; correct secret recovered.
- CUSTOMER/ADMIN internal denial and service admin denial: verified directly.
- Gateway internal Pricing route: absent; no internal calculation success through Gateway.
- Private key configured only in Auth; packaged verifiers contain no private PEM.
- Client secret injected only into Auth/Quote, absent from Pricing/Gateway/Audit environment.
- Frontend contains no service credential/config changes. Logs exclude service/user tokens, client secret and private material.

Exact single-acquisition count is tested under controlled concurrency in the HTTP unit
test. E2E cache reuse is proven by successful requests while Auth is stopped rather
than by adding production counters or token-issuance events. No new event is emitted.

## Limitations and deferred work

The expected secret is deployment configuration in process memory and compared with
constant-time byte comparison; no client-management database or hash store was added.
Use external secret management, TLS and token-endpoint brute-force controls for real
deployment. Local defaults are publicly known DEV-only values. Service and user JWTs
share Auth's RSA key and issuer while claims/audience enforce separation. Key/credential
rotation, JWKS, stronger issuer/key separation, mTLS, mesh and distributed rate limiting
remain deferred. No service refresh token or Phase 26 observability/resilience code
was introduced. See the specification for complete configuration and runtime flow.

## Created/modified files

- `.env.example`
- `README.md`
- `docs/architecture.md`
- `docs/implementation-roadmap.md`
- `docs/project-context.md`
- `docs/security.md`
- `docs/specs/README.md`
- `services/auth-service/README.md`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/AuthSecurityConfiguration.java`
- `services/auth-service/src/main/resources/application-local.yml`
- `services/auth-service/src/main/resources/application.yml`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthServiceApplicationTests.java`
- `services/pricing-service/README.md`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/JwtConfiguration.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/PricingSecurityConfiguration.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingServiceApplicationTests.java`
- `services/quote-service/README.md`
- `services/quote-service/src/main/java/com/insurance/platform/quote/client/PricingClient.java`
- `services/quote-service/src/main/resources/application-local.yml`
- `services/quote-service/src/main/resources/application.yml`
- `services/quote-service/src/test/java/com/insurance/platform/quote/PricingClientTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteServiceApplicationTests.java`
- `docs/phase-25-validation.md`
- `docs/specs/phase-25-service-authentication.md`
- `infrastructure/validation/phase25-service-auth-e2e.py`
- `services/auth-service/src/main/java/com/insurance/platform/auth/controller/ServiceTokenController.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/client/ServiceTokenClient.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/ServiceAuthenticationTests.java`
