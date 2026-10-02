# Security

Status: Approved high-level intent recorded; detailed design pending migration from approved planning notes.

Spring Security, customer registration and authentication, quote ownership security, and admin pricing-rule management are planned. The main users are CUSTOMER and ADMIN.

Authentication mechanisms and detailed authorization rules remain unspecified. No authentication or security implementation is included in Phase 0.

Do not commit credentials, private keys, access tokens, refresh tokens, or database passwords. Any future example environment files must contain safe placeholders only.

## Phase 1 — Local Development Boundary

The Compose defaults and `.env.example` contain public development-only values, explicitly permitted for Phase 1. Real secrets must never be committed, and `.env` remains ignored. Published PostgreSQL and Kafka ports bind to loopback. Kafka uses plaintext without authentication, and PostgreSQL uses a shared local administrative account. This setup makes no production security or database-isolation enforcement claim; application authentication and service-specific authorization remain deferred.
