# Phase 23 validation — 22 October 2026

Implemented Phase 23 only. No migration, asymmetric JWT, business-service changes,
new events or outbox semantics were introduced.

## Executed checks

- `./mvnw verify`: PASS across Gateway, Auth, Quote, Pricing, Notification and Audit.
  423 total tests. Gateway: 47 tests; Auth: 40 tests. Existing regression suites pass.
- `./mvnw -pl services/auth-service -Ppostgres-it verify`: PASS, 9 PostgreSQL
  integration tests against disposable postgres:17.11-bookworm (plus normal Auth tests).
  New cases prove opaque/hash-only persistence, rotation, old-token replay rejection,
  expiration, logout idempotency, disabled rejection surviving re-enable, and exactly
  one concurrent winner. A forced replacement INSERT check-constraint failure rolls
  back revocation; the original token remains usable afterward.
- `npm --prefix frontend test`: PASS, 109 tests, including new session restoration for ADMIN and
  CUSTOMER, failed restoration, refresh failure without recursion, three queued 401s
  sharing one refresh and retrying, public auth exclusions, no refresh on 403, delayed
  responses and logout failure. Guard tests wait for renewal before deciding navigation.
- `npm --prefix frontend run build`: PASS, Angular production bundle.
- `python3 infrastructure/validation/phase23-refresh-e2e.py`: PASS with a new disposable
  PostgreSQL container and real packaged Auth/Gateway processes on temporary ports.
  Script stops only its own processes/container and never uses the learning database.
  Register → login → HttpOnly/SameSite cookie → authenticated CUSTOMER gets expected
  ADMIN endpoint 403 → 2-second access JWT expires to 401 → refresh → rotated cookie →
  old cookie 401 → next rotation succeeds → logout 204 → cookie jar empty → refresh
  401 → repeated logout 204. Real ADMIN disable/enable rejects and revokes the
  CUSTOMER refresh cookie; enabling does not resurrect it and fresh login succeeds.
  An already-issued CUSTOMER JWT continues authenticating until expiry.
  Raw refresh token absent from Auth and Gateway logs.

Normal verification builds the jars required by the HTTP script. The script sets
short access TTL only in its disposable environment; the production default remains
15 minutes. It checks local HTTP Secure=false; normal Auth HTTP tests check Secure,
HttpOnly, SameSite=Lax, auth-only path, TTL and deletion attributes under the test
profile. Cookie/token values are not printed in validation evidence.

Gateway tests prove refresh/logout public routing, Cookie and Set-Cookie forwarding,
correlation preservation, exact-origin credentialed CORS and disallowed-origin
rejection. Login/register remain public; unrelated APIs retain their role boundaries.
Auth HTTP tests verify hash storage and no raw refresh token in JSON, safe malformed
and unknown refresh errors, logout deletion and existing safe password/JWT contracts.

## Browser equivalence and limitations

No manual browser walkthrough or browser automation is claimed. Angular HTTP/state/
interceptor/guard tests and real Gateway/Auth HTTP validation provide equivalent
session-flow evidence. Startup uses an application initializer (10-second timeout)
that completes before initial routed navigation; restored sessions do not trigger
login-success navigation. Expired in-memory sessions also renew before later guard
checks. Access tokens never enter localStorage, sessionStorage or IndexedDB.

Already-issued access JWTs remain valid until expiry after disable/logout. A refresh
actually observed while disabled is revoked, and does not recover after enable.
Other unrevoked sessions may still work after enable. Replayed tokens are rejected;
there is no family-wide revocation, cross-tab coordination or cleanup job. Network
logout failure clears memory but cannot delete an HttpOnly server cookie. Deployment
assumes same-site frontend/Gateway with exact configured CORS origins.

See [Phase 23 specification](specs/phase-23-refresh-tokens.md) for configuration and
transaction behavior. Phase 24 remains unstarted.

## Files created or modified

- `docs/architecture.md`
- `docs/implementation-roadmap.md`
- `docs/phase-23-validation.md`
- `docs/security.md`
- `docs/specs/phase-23-refresh-tokens.md`
- `frontend/README.md`
- `frontend/src/app/app.component.ts`
- `frontend/src/app/app.config.ts`
- `frontend/src/app/core/auth/auth-api.service.ts`
- `frontend/src/app/core/auth/auth.spec.ts`
- `frontend/src/app/core/auth/session.service.spec.ts`
- `frontend/src/app/core/auth/session.service.ts`
- `frontend/src/app/core/guards/admin.guard.ts`
- `frontend/src/app/core/guards/auth.guard.spec.ts`
- `frontend/src/app/core/guards/auth.guard.ts`
- `frontend/src/app/core/guards/customer.guard.ts`
- `frontend/src/app/core/guards/refresh-guard-session.ts`
- `frontend/src/app/core/interceptors/auth.interceptor.spec.ts`
- `frontend/src/app/core/interceptors/auth.interceptor.ts`
- `frontend/src/app/features/audit/audit.spec.ts`
- `frontend/src/app/store/auth/auth.actions.ts`
- `frontend/src/app/store/auth/auth.reducer.ts`
- `infrastructure/validation/phase23-refresh-e2e.py`
- `services/api-gateway/README.md`
- `services/api-gateway/src/main/java/com/insurance/platform/gateway/config/GatewaySecurityConfiguration.java`
- `services/api-gateway/src/test/java/com/insurance/platform/gateway/ApiGatewayApplicationTests.java`
- `services/auth-service/README.md`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/AuthSecurityConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/controller/AuthController.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/entity/RefreshToken.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/repository/RefreshTokenRepository.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/service/RefreshSessionService.java`
- `services/auth-service/src/main/resources/application-local.yml`
- `services/auth-service/src/main/resources/application.yml`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthPostgresIT.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthServiceApplicationTests.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/CustomerAdministrationTests.java`
