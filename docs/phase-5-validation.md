# Phase 5 — API Gateway and JWT Validation

Status: Complete. Validated locally on 2026-10-04 with Java 25.0.1. Phase 6 has not started.

## Automated validation

`./mvnw -B clean verify` passed for the root aggregator and all six service modules: **47 tests, zero failures/errors/skips**. Gateway has 24 tests; Auth has its existing 19; the four other services retain one each.

Gateway tests use a real HTTP server, an ephemeral HTTP downstream stub, and a generated signing key. They do not require Auth, PostgreSQL, Kafka, or Docker. They verify:

- Public register/login routing and registration body forwarding.
- Public health/info; default protection including GET on the public POST registration path.
- Valid JWT forwarding with the original Authorization header and correlation ID.
- Missing, malformed, expired (zero clock skew), tampered, and wrong-issuer tokens return the same safe 401 JSON.
- Missing exp/sub/roles and malformed roles are rejected.
- Invalid supplied Bearer tokens are rejected even on public endpoints.
- Allowed preflight succeeds without authentication; unapproved CORS origin fails; credentials are not enabled.
- Unsafe correlation identifiers are replaced with UUIDs.

The test downstream URL overrides `AUTH_SERVICE_URL`, exercising configurable routing. The protected `/api/v1/auth/probe` handler exists only in the test stub, not in production application code.

An initial missing-iat test highlighted Spring's default claim conversion, which supplies iat when absent. Gateway intentionally does not require iat; Auth still issues it. Required expiration, issuer, subject, roles, signature, and algorithm checks are explicit.

## Live validation

Ran `docker compose up -d` against existing infrastructure; PostgreSQL and Kafka were healthy. Reused the already-running Auth Service on 8081 without restarting it, and started the newly built Gateway jar on 8080 with the default local profile/key.

| Check | Result |
| --- | --- |
| POST register through 8080 | 201; synthetic CUSTOMER created |
| POST login through 8080 | 200; Bearer JWT returned |
| Gateway health / info | 200; health UP |
| Auth direct health (troubleshooting) | 200; UP |
| Protected unmapped path, no token | 401 |
| Same path, valid Auth-issued JWT | 404: authentication accepted, no configured route |
| Malformed or tampered JWT | 401 |
| Correctly signed but expired JWT | 401 |
| Correctly signed JWT with wrong issuer | 401 |
| Correlation ID through registration | Same ID returned |
| Login repeated after negative checks | 200; Auth still reachable |
| Auth Flyway history/checksums | Unchanged |
| Refresh-token and outbox row counts | Unchanged |

The live check verified the issued token's signature using the shared local key before constructing negative tokens. No token, password, or signing secret was printed. One uniquely named synthetic account was deleted afterward. The temporary Gateway process was stopped; the pre-existing Auth process and infrastructure were left running.

## Scope and limitations

Only Gateway production code/configuration and documentation/example configuration changed. Auth production code, other services, migrations, Maven versions, and Docker infrastructure are unchanged. The only committed key is the pre-existing public learning key, now also used in Gateway's local YAML; no real secret was introduced. `git diff --check` passes.

There are no protected business endpoints yet. Live acceptance is demonstrated by authenticated 404 versus unauthenticated 401; real authenticated downstream forwarding is verified using the automated stub. This does not establish business authorization or downstream JWT validation. Gateway health alone does not check Auth availability.

Deferred: Pricing Service MVP (Phase 6), other business routes, downstream/service-to-service authentication, refresh/logout/rotation, asymmetric signing, Kafka messaging/outbox publication, Angular, discovery, tracing, Redis, and rate limiting. No Phase 6 implementation was started.
