# Security

Status: Approved high-level intent recorded; detailed design pending migration from approved planning notes.

Spring Security, customer registration and authentication, quote ownership security, and admin pricing-rule management are planned. The main users are CUSTOMER and ADMIN.

Authentication mechanisms and detailed authorization rules remain unspecified. No authentication or security implementation is included in Phase 0.

Do not commit credentials, private keys, access tokens, refresh tokens, or database passwords. Any future example environment files must contain safe placeholders only.

## Phase 1 — Local Development Boundary

The Compose defaults and `.env.example` contain public development-only values, explicitly permitted for Phase 1. Real secrets must never be committed, and `.env` remains ignored. Published PostgreSQL and Kafka ports bind to loopback. Kafka uses plaintext without authentication, and PostgreSQL uses a shared local administrative account. This setup makes no production security or database-isolation enforcement claim; application authentication and service-specific authorization remain deferred.

## Phase 2 — Temporary Skeleton Policy

The five servlet services include Spring Security with a minimal temporary filter chain: `/actuator/health` and `/actuator/info` are public, other requests are denied, and form login/HTTP Basic are disabled. Default user auto-configuration is excluded; no password is generated. This is a startup baseline, not real authentication or business authorization. Replace it with approved service security in later phases. Notification uses the same baseline for consistency.

Gateway has no business routes, login, or JWT behavior. All six local profiles bind to loopback unless `SERVER_ADDRESS` is overridden. Only health/info are exposed, and health details are hidden by default.

## Phase 4 — Auth Service Only

Auth's temporary Phase 2 chain is replaced. Only POST registration/login and GET health/info are public. Other requests require authentication; bearer-token validation is not installed yet. The API is stateless with no session/cookie authentication, form login, HTTP Basic, or logout handler. CSRF is disabled for this JSON API; it must be reconsidered if browser cookie authentication is introduced.

Registration strips/lowercases email with Locale.ROOT and always persists ACTIVE CUSTOMER. Unknown properties, including role/status, are rejected. Repository checks ignore email case; canonical stored values and the existing unique constraint protect duplicate races. Passwords use BCrypt cost 12, require at least 12 characters for registration, and are capped at 72 UTF-8 bytes without trimming/truncation. Login returns identical 401 errors for missing, incorrect-password, and disabled accounts, with a dummy BCrypt check for missing accounts.

Access tokens use Spring Security OAuth2 JOSE/Nimbus HS256, a Base64 secret of at least 32 decoded bytes, and a configurable 15-minute default lifetime. Claims contain only user ID, roles, issue/expiry times, and issuer. Invalid keys fail startup. Auth now defaults to the local profile with an explicitly public development-only key, per the learning-project preference. Outside that profile a signing key must be supplied. Never reuse the local key for a real deployment. No key, password, hash, token, or authorization header is logged by Auth code. Generic errors do not echo rejected values or exceptions. Token responses are marked no-store.

ADMIN development provisioning is an explicit manual promotion of a dedicated registered account using local database administrator access; no startup default password or public admin endpoint exists. See [Auth setup](../services/auth-service/README.md). Existing tokens are not revoked when an account is disabled; subsequent login is denied. Rate limiting, refresh tokens, revocation, distributed validation, and production provisioning remain outside Phase 4.

## Phase 5 — Gateway Authentication

Gateway now routes `/api/v1/auth/**` to configurable Auth Service URL (default localhost:8081). POST register/login and GET health/info are public; other requests require a validated HS256 JWT. The local profile shares Auth's public development key; outside local both services require a configured secret. Subject and roles are parsed, with no business role authorization. Authorization is forwarded unchanged; downstream token validation remains future work. Minimal configurable CORS and bounded correlation IDs are implemented. No business routes, messaging, schema changes, or discovery were added. See [Gateway details](../services/api-gateway/README.md) and [validation](phase-5-validation.md). Earlier phase sections describe historical milestones; current status is Phase 5 complete, Phase 6 not started.
