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
