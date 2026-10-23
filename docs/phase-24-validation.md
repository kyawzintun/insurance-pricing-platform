# Phase 24 validation — 23 October 2026

Phase 24 — Asymmetric JWT is complete. Phase 25 — Service-to-Service Authentication
is not started. This is the phase-completion assessment for the learning platform;
production deployment still requires external keys and the existing infrastructure
hardening described in the security documentation.

## Implementation results

| Concern | Result |
| --- | --- |
| Auth signing | Nimbus RSA private-key encoder; `JwtService` explicitly sets RS256; login/refresh share it |
| Auth verification | Separate public-key decoder for protected admin endpoints; no private-key validation path |
| Gateway verification | Reactive Nimbus decoder with public key; RS256 only; existing safe errors/correlation forwarding |
| Quote verification | Independent public-key decoding; existing CUSTOMER ownership and ADMIN access preserved |
| Pricing verification | Independent public-key decoding; ADMIN enforcement, UUID actor handling and event identity preserved |
| Audit verification | Independent public-key decoding; ADMIN reads, denied mutations and read behavior preserved |
| Notification | Inspected; health/info public, other HTTP requests denied; no JWT logic added |
| Algorithms | Explicit RS256 allowlist; HS256/public-key confusion, none, RS512, malformed and wrong RSA rejected |
| Key startup | Missing/invalid/non-RSA/undersized keys fail; Auth checks modulus and performs a pair signature probe |
| Claims | Existing `sub`, `roles`, `iat`, `exp`, `iss`, issuer and TTL unchanged; zero-skew expiry retained |
| Refresh | Opaque random cookie, hash-only DB persistence, rotation/replay/logout/disabled handling unchanged |
| Data scope | No DB migration, event contract, outbox, retry/DLQ or business API changes |
| Frontend | Production behavior unchanged; illustrative test token header updated to RS256; no browser verification |

## Configuration and local/test keys

- `AUTH_JWT_PRIVATE_KEY`: Auth-only Spring resource location for unencrypted PKCS#8 RSA PEM.
- `AUTH_JWT_PUBLIC_KEY`: Spring resource location for X.509 SubjectPublicKeyInfo RSA PEM,
  supplied to Auth, Gateway, Quote, Pricing and Audit.
- `AUTH_JWT_ISSUER`: unchanged default `insurance-auth-service`.
- `AUTH_JWT_ACCESS_TOKEN_TTL`: unchanged default `15m`, Auth only.
- At least 2048-bit RSA. Non-local profiles require external key locations; production
  must explicitly select a non-local profile. No symmetric fallback exists.
- The established default local profile uses clearly labelled, publicly known DEV-only
  fixtures. Auth alone packages a development private key. Verifiers package public
  fixtures only. Deterministic private test fixtures are under `src/test/resources`.
- The E2E harness creates ephemeral external keys, supplies the private property only
  to Auth and removes ephemeral private files afterward. It does not print key material.

## Executed checks

| Command | Result |
| --- | --- |
| `./mvnw verify` | PASS: 439 tests; Gateway 50, Auth 44, Quote 118, Pricing 127, Notification 43, Audit 57 |
| `./mvnw -pl services/auth-service -Ppostgres-it verify` | PASS: normal Auth suite plus 9 PostgreSQL integration tests against disposable PostgreSQL 17 |
| `npm --prefix frontend test` | PASS: 109 tests across 11 files |
| `npm --prefix frontend run build` | PASS: Angular production bundle |
| `python3 infrastructure/validation/phase24-rs256-e2e.py` | PASS: disposable PostgreSQL/Kafka and five real packaged services |
| `python3 infrastructure/validation/phase23-refresh-e2e.py` | PASS: preserved Phase 23 real Auth/Gateway refresh regression using RSA configuration |
| `git diff --check` | PASS |

The normal suites preserve existing authorization/ownership, malformed-subject,
issuer/expiry, correlation, pricing event actor and audit-read cases with RSA-signed
fixtures. New decoder tests in all five services cover algorithm confusion, unsigned
and unsupported algorithms, wrong signatures, malformed tokens and invalid key/issuer
configuration. New HTTP tests independently reject HS256, none, RS512, unrelated RSA
and malformed tokens in all five services. Auth additionally tests pair mismatch,
unrelated public-key rejection, claims and TTL; PostgreSQL login and refresh results
are parsed for RS256 and cryptographically verified with the configured public key.

PostgreSQL integration preserves rotation, replay rejection, logout, disabled refresh
revocation, expiration, concurrency and transaction rollback behavior. No migration
was added. The preserved Phase 23 HTTP harness also verifies short-lived access expiry,
HttpOnly cookie delivery, repeated rotation, replay rejection, logout/cookie deletion,
ADMIN disable/enable and required new login after disabled refresh revocation.

The disposable Phase 24 flow registers a CUSTOMER, logs in, verifies the RS256 header
and RSA signature, calls Quote through Gateway, refreshes and verifies the new token,
checks direct Quote access, rejects the old refresh cookie, provisions a disposable
ADMIN through the existing database procedure, and reads customer/pricing/audit APIs
through Gateway and directly. Both paths reject wrong RSA, HS256/public-key HMAC,
none and malformed JWTs with safe 401s. CUSTOMER still receives 403 on admin APIs.
Gateway correlation response/forwarding behavior is preserved; direct backends retain
their existing response-header behavior.

## Private-key isolation and security evidence

The harness inspects the exact environment configuration passed to each process:

```text
private key configured in Auth: yes
public key configured in verifier services: yes
verifier process environments require/receive private key: no
```

It also inspects the packaged Gateway/Quote/Pricing/Audit jars for private fixture
files, scans service logs for private PEM markers and key content without printing
that content, checks API responses for private material and safe errors, and scans
frontend source for private keys. Refresh-cookie values are absent from captured logs.
Production Java has no `withSecretKey`, HS256, HMAC or `AUTH_JWT_SECRET` signing path.

Normal Maven/HTTP and Docker validation required running outside the restrictive
filesystem/network sandbox. The initial frontend build stalled inside the sandbox;
the unrestricted production build passed. Earlier E2E attempts exposed harness-only
issues (cookie module/function name collision, a direct-backend correlation response
assumption) and an overlap with Maven repackaging; the final E2E run used stable jars
and passed. No application authorization behavior was relaxed to pass the harness.

## Rollout and limitations

Old HS256 access JWTs are rejected immediately after deployment. Users can refresh
or log in for RS256 access tokens; otherwise valid opaque refresh tokens survive the
algorithm change. Supply the same public key/issuer to all verifiers and deploy the
matching private key only to Auth. Never deploy the checked-in DEV-only private key.

One key pair is active. No JWKS, remote key fetching, `kid`, multiple active keys,
automatic rotation, OAuth2 server, service credentials, blacklist or JWE is added.
Changing the key invalidates current access tokens. Existing access tokens remain
valid until expiry after logout/disable, as before. Full service-to-service protection
is deferred to Phase 25; that phase has not been implemented. No manual browser
walkthrough is claimed; frontend tests/build and real HTTP flows provide the recorded
regression evidence. Historical Phase 19–22 harnesses were adapted for public/private
key configuration but not re-executed in this phase.

See [specification](specs/phase-24-asymmetric-jwt.md) for ownership, architecture,
configuration, deployment examples and startup behavior.

## Files created or modified

- `README.md`
- `docs/architecture.md`
- `docs/implementation-roadmap.md`
- `docs/phase-24-validation.md`
- `docs/security.md`
- `docs/specs/README.md`
- `docs/specs/phase-24-asymmetric-jwt.md`
- `frontend/src/app/testing/auth-fixtures.ts`
- `infrastructure/validation/phase19-e2e.py`
- `infrastructure/validation/phase20-e2e.py`
- `infrastructure/validation/phase21-e2e.py`
- `infrastructure/validation/phase22-e2e.py`
- `infrastructure/validation/phase23-refresh-e2e.py`
- `infrastructure/validation/phase24-rs256-e2e.py`
- `services/README.md`
- `services/api-gateway/README.md`
- `services/api-gateway/src/main/java/com/insurance/platform/gateway/config/JwtConfiguration.java`
- `services/api-gateway/src/main/java/com/insurance/platform/gateway/config/RsaKeys.java`
- `services/api-gateway/src/main/resources/application-local.yml`
- `services/api-gateway/src/main/resources/application.yml`
- `services/api-gateway/src/main/resources/keys/README.md`
- `services/api-gateway/src/test/java/com/insurance/platform/gateway/ApiGatewayApplicationTests.java`
- `services/api-gateway/src/test/java/com/insurance/platform/gateway/RsaTestTokens.java`
- `services/api-gateway/src/test/java/com/insurance/platform/gateway/config/RsaJwtConfigurationTests.java`
- `services/api-gateway/src/test/resources/application-test.yml`
- `services/api-gateway/src/test/resources/keys/README.md`
- `services/audit-service/README.md`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/JwtConfiguration.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/RsaKeys.java`
- `services/audit-service/src/main/resources/application-local.yml`
- `services/audit-service/src/main/resources/application.yml`
- `services/audit-service/src/main/resources/keys/README.md`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditReadTests.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/RsaTestTokens.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/config/RsaJwtConfigurationTests.java`
- `services/audit-service/src/test/resources/application-integration.yml`
- `services/audit-service/src/test/resources/application-test.yml`
- `services/audit-service/src/test/resources/keys/README.md`
- `services/auth-service/README.md`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/AuthJwtDecoderConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/JwtConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/RsaKeys.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/service/JwtService.java`
- `services/auth-service/src/main/resources/application-local.yml`
- `services/auth-service/src/main/resources/application.yml`
- `services/auth-service/src/main/resources/keys/README.md`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthAdminJwtSupport.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthKafkaIT.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthPostgresIT.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthServiceApplicationTests.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthTestIdentity.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/CustomerAdministrationTests.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/RsaTestTokens.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/config/RsaJwtConfigurationTests.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/service/JwtServiceTests.java`
- `services/auth-service/src/test/resources/application-integration.yml`
- `services/auth-service/src/test/resources/application-test.yml`
- `services/auth-service/src/test/resources/keys/README.md`
- `services/pricing-service/README.md`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/JwtConfiguration.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/RsaKeys.java`
- `services/pricing-service/src/main/resources/application-local.yml`
- `services/pricing-service/src/main/resources/application.yml`
- `services/pricing-service/src/main/resources/keys/README.md`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/AdminJwtSupport.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingAdministrationTests.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingKafkaIT.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingRulePostgresIT.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingServiceApplicationTests.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingTestIdentity.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/RsaTestTokens.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/config/RsaJwtConfigurationTests.java`
- `services/pricing-service/src/test/resources/application-integration.yml`
- `services/pricing-service/src/test/resources/application-test.yml`
- `services/pricing-service/src/test/resources/keys/README.md`
- `services/quote-service/README.md`
- `services/quote-service/src/main/java/com/insurance/platform/quote/config/JwtConfiguration.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/config/RsaKeys.java`
- `services/quote-service/src/main/resources/application-local.yml`
- `services/quote-service/src/main/resources/application.yml`
- `services/quote-service/src/main/resources/keys/README.md`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteJwtSupport.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteKafkaIT.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuotePostgresIT.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteRetrievalTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteServiceApplicationTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/RsaTestTokens.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/config/RsaJwtConfigurationTests.java`
- `services/quote-service/src/test/resources/application-integration.yml`
- `services/quote-service/src/test/resources/application-test.yml`
- `services/quote-service/src/test/resources/keys/README.md`

Additional committed setup files:

- `.gitignore`
- `services/api-gateway/src/main/resources/keys/dev-jwt-public.pem`
- `services/api-gateway/src/test/resources/keys/dev-jwt-private.pem`
- `services/api-gateway/src/test/resources/keys/dev-jwt-public.pem`
- `services/audit-service/src/main/resources/keys/dev-jwt-public.pem`
- `services/audit-service/src/test/resources/keys/dev-jwt-private.pem`
- `services/audit-service/src/test/resources/keys/dev-jwt-public.pem`
- `services/auth-service/src/main/resources/keys/dev-jwt-private.pem`
- `services/auth-service/src/main/resources/keys/dev-jwt-public.pem`
- `services/auth-service/src/test/resources/keys/dev-jwt-private.pem`
- `services/auth-service/src/test/resources/keys/dev-jwt-public.pem`
- `services/pricing-service/src/main/resources/keys/dev-jwt-public.pem`
- `services/pricing-service/src/test/resources/keys/dev-jwt-private.pem`
- `services/pricing-service/src/test/resources/keys/dev-jwt-public.pem`
- `services/quote-service/src/main/resources/keys/dev-jwt-public.pem`
- `services/quote-service/src/test/resources/keys/dev-jwt-private.pem`
- `services/quote-service/src/test/resources/keys/dev-jwt-public.pem`
