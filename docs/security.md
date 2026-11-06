# Security Architecture and Checklist

This describes the current implementation. Historical security decisions remain in phase specifications and validation reports; they are not current deployment instructions. This project makes no production certification or compliance claim.

| Control | Implemented behavior | Evidence / boundary |
| --- | --- | --- |
| Password storage | BCrypt cost 12; registration minimum 12 characters, maximum 72 UTF-8 bytes; no silent truncation | Auth validation and password tests |
| Login errors | Unknown account, bad password and disabled account return the same safe 401 | Dummy BCrypt check for unknown accounts |
| Refresh storage | Opaque random tokens stored as SHA-256 digests only | Raw refresh token only travels in cookie |
| Cookie | HttpOnly; path `/api/v1/auth`; SameSite Lax by default; seven-day default TTL | Secure true normally; false only in local HTTP profile |
| Rotation | Row-lock transaction revokes old token and inserts replacement | Replayed old token rejected; replacement failure rolls back |
| Access JWT | RS256 only; issuer/expiry/subject and appropriate identity/role validation | Default lifetime 15 minutes; old HS256 rejected |
| Signing key | Private RSA PEM configured only in Auth production resources | Verifiers receive public key only; test resources contain DEV fixtures |
| Service credentials | Auth and Quote alone receive client secret | Quote uses direct Auth issuance; service JWT is scoped/audience restricted |
| Authorization | CUSTOMER quote creation and ownership; ADMIN pricing/customer/audit/Operations | Gateway and owning services enforce access; Angular guards are UI only |
| Sensitive logs | Application code avoids credentials, tokens, full payloads and raw persistence errors | PostgreSQL rejected-row details disabled; diagnostic metadata allowlisted |
| Safe errors | Stable API errors without stack traces or rejected secrets | Bounded safe correlation IDs preserved across flows |
| CORS | Gateway exact-origin allowlist with credentials; localhost:4200 locally | No wildcard credentialed origin |
| Metrics | ADMIN on authenticated services; Notification numeric metrics internal only | Keep service/Actuator ports private |

## User Sessions

Access tokens live only in Angular memory, not localStorage/sessionStorage. Initialization restores via cookie before navigation. SessionService shares one refresh for concurrent 401s, retries protected requests, and clears session/domain stores on failure/logout. A 403 does not refresh. Bearer injection is restricted to Gateway API origin/path and excludes auth session operations.

Refresh/logout are POST-only. SameSite Lax/Strict cookies and constrained credentialed CORS are the project's CSRF assumptions; no synchronizer CSRF token is implemented. This assumes the documented localhost/same-site deployment. A different cross-site topology requires a fresh cookie/CORS/CSRF assessment; SameSite=None is not supported. See [refresh specification](specs/phase-23-refresh-tokens.md).

## RS256 and Key Isolation

`AUTH_JWT_PUBLIC_KEY=file:/absolute/path/public.pem` is configured in Auth, Gateway, Quote, Pricing and Audit. Only Auth receives `AUTH_JWT_PRIVATE_KEY=file:/absolute/path/private.pem`. Keys are RSA (at least 2048 bits), PKCS#8 private and X.509 SubjectPublicKeyInfo public PEM. Non-local configuration requires external keys. `AUTH_JWT_SECRET` is obsolete and unsupported.

Local classpath keys and every `keys/dev-jwt-*` fixture are **publicly known DEV/TEST ONLY**, including test-only verifier private fixtures. They are deliberately committed for reproducibility and must never sign real credentials. See [RS256 validation](phase-24-validation.md) and the key-directory READMEs.

## Service Authentication

Quote sends configured client credentials directly to Auth's `POST /api/v1/auth/service-token` using the implemented client-credentials form. This route is not exposed through Gateway. Auth issues a short-lived RS256 token; Quote caches it in memory with single-flight acquisition and expiry margin. Pricing validates service subject `quote-service`, scope `pricing:calculate` and audience `pricing-service`. User tokens cannot call calculation; service tokens cannot call admin APIs. A Pricing 401 permits one token reacquisition/retry. Independent Auth/Pricing timeouts and the Pricing circuit breaker bound failures.

Only Auth/Quote need `QUOTE_SERVICE_CLIENT_SECRET`. The local default is public development-only; use externally supplied credentials for any other environment. See [service-auth specification](specs/phase-25-service-authentication.md).

## Known Limitations and Local Boundary

- Logout/disable does not immediately revoke existing access JWTs. They may authorize until expiry; disable blocks new login and refresh, and disabled-user refresh is revoked.
- Reusing an old refresh token fails, but does not revoke a whole token family. No family tracking, device management or expired-token cleanup job.
- No JWKS, automatic signing-key rotation, mTLS, login rate limiting or production admin-provisioning workflow.
- Compose Kafka is plaintext, unauthenticated and replication factor one; PostgreSQL uses a shared local administrative account. Published infrastructure and local applications bind loopback by default.
- Outboxes and DLQ retain data; DLQ preserves original bytes and may contain sensitive input. Access/retention and replay operations need deployment policies.
- Event contracts omit credentials; user-event payloads include email where specified. Audit metadata omits that email. Do not log whole records.

[Local configuration](local-development.md) documents public defaults and DEV-only admin promotion. [Final audit results](portfolio-validation.md) records the tracked-file secret scan, which is a repository review, not a security certification or a guarantee about Git history.
