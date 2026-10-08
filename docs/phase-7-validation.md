# Phase 7 — Pricing Administration Validation

Status: Complete. Validated on 2026-10-07 with Java 25.0.1. Phase 8 — Quote Service MVP has not started.

## Automated build and tests

Final `./mvnw -B clean verify` passed for the root aggregator and all six services: **164 tests**, zero failures/errors/skips.

| Module | Tests |
| --- | ---: |
| API Gateway | 33 |
| Auth | 19 |
| Quote | 1 |
| Pricing | 109 |
| Notification | 1 |
| Audit | 1 |

Pricing includes the 62 existing Phase 6 tests and 47 new real-HTTP administration cases. The latter run real servlet security, JWT decoding, controller, validation, and admin service with a mocked repository and ephemeral key. They cover missing/CUSTOMER/ADMIN authentication, tampering, expiry, wrong issuer, malformed claims, future-effective creation, generated metadata, rejected client metadata, invalid factors/operators/base/BETWEEN/coverage, pagination/filtering and invalid query inputs, get/missing, update/stale versions, enable/disable, and safe optimistic-lock errors. Mock-based tests do not claim to prove persistence version increments.

Gateway includes nine new cases for admin routing, missing/CUSTOMER/ADMIN access, original-token forwarding across methods, PUT/PATCH CORS preflight, and exclusion of the internal calculation route. Existing Auth routing and JWT tests remain passing. Normal tests require no running database, Kafka, Docker, or H2.

## Isolated PostgreSQL tests

`./mvnw -B -pl services/pricing-service -am -Ppostgres-it verify` passed, including **two additional PostgreSQL integration tests**. Testcontainers started a disposable PostgreSQL 17.11 container, applied the existing Flyway migrations, and removed the container afterward. No live developer database was used by this test profile.

- Persisted create/get/list/filter, generated timestamps/version 0, version increments on update and toggles (including repeated same-state toggle), preserved createdAt, stale-version conflict, future/disabled selection, and baseline/restored premium 11200 versus effective multiplier premium 22400.
- Two concurrent database transactions read the same version, synchronize at a barrier, and update simultaneously. Exactly one succeeds; the other raises an optimistic-lock failure. The persisted version increases exactly once. HTTP tests separately verify the safe 409 mapping of that failure.

Outbox stayed empty and the disposable database retained exactly the three successful existing pricing migrations.

## Live validation through Gateway and direct Pricing

Existing PostgreSQL and Kafka were running. With explicit user approval, restarted Gateway on 8080 and Pricing on 8083 using the newly built jars; left the existing Auth process on 8081 running. All three health endpoints returned UP. Gateway/Pricing remain running after validation.

Registered two uniquely named fictional validation accounts through Gateway. Promoted only the temporary administrator using local database maintenance, then logged in again to obtain real Auth-issued ADMIN/CUSTOMER JWTs. No token/password was printed or added to the repository.

| Check | Result |
| --- | --- |
| No JWT, admin GET via Gateway and direct Pricing | 401 |
| CUSTOMER JWT at either boundary | 403 |
| ADMIN JWT at either boundary | 200 |
| Tampered/malformed/expired/wrong-issuer token at either boundary | 401 |
| POST temporary future-effective rule through Gateway | 201, Location, generated ID/version/timestamps |
| GET created rule | 200, same data |
| Paginated/filtering GET | 200, bounded page and metadata |
| PATCH disable | 200; version 0 → 1 |
| PUT past-effective, disabled rule | 200; version 1 → 2 |
| PATCH enable | 200; version 2 → 3 |
| PUT changed factor | 200; version 3 → 4 |
| Stale PUT | 409 PRICING_RULE_VERSION_CONFLICT |
| PATCH disable | 200; version 4 → 5 |
| Stale PATCH | 409 |
| Missing rule | 404 |
| Invalid factor / excessive page size | 400 |
| Internal calculation path through Gateway, ADMIN token | 404; no route |

All mutations used one uniquely marked temporary VEHICLE_VALUE rule. Existing seed rows were never edited. Baseline comprehensive premium was **11200 THB**. Future and disabled rules left it unchanged; enabling a currently effective factor 2 rule produced **22400 THB**; updating the factor to 1.5 produced **16800 THB**; disabling restored **11200 THB**. This exercised actual persisted rules, not a mock.

## Database and Kafka safety

Compared ordered content fingerprints before and after cleanup: all original pricing_rules rows were restored exactly. The temporary rule and both temporary accounts were removed with narrowly targeted local database maintenance; no delete endpoint was added. Outbox content/count and Flyway history fingerprints stayed unchanged throughout. Kafka topic offsets before/after were identical.

No migration/seed file, infrastructure configuration, Auth/Quote/Notification/Audit production code, or root dependency version was changed. No Kafka producer/listener or outbox writer was added. Admin persistence touches only pricing_rules. No real credential or token was introduced; Pricing reuses the explicitly public learning key only in local YAML. `git diff --check` passes.

## Choices and limitations

Pagination defaults page 0/size 20, maximum size 100, fixed ID ascending order, optional exact ruleType/enabled filters. PUT is full replacement; PATCH requires expected version. Hibernate @Version is the final concurrency check, with an early expected-version check for clear stale responses. Timestamps use PostgreSQL microsecond precision; repeated same-state writes still advance timestamp/version. Past/current/future effective dates within ISO years 0001–9999 are accepted.

Phase 6 newest-effective matching selection, UUID tie-break, category order, and per-step rounding remain unchanged. Multiple rules and multiple bases remain allowed; disabling/retyping the last active base may make calculation unavailable until a valid base exists. There is no global rule conflict engine or combined-premium guarantee.

Pricing independently validates ADMIN user JWTs; this is not service-to-service authentication. The internal calculation endpoint remains temporarily unauthenticated and off Gateway until later service authentication work (Phase 25). No Angular UI, Quote integration/persistence, refresh tokens, asymmetric signing, Kafka/outbox events, audit integration, cache, or Phase 8 implementation.

See [Pricing guide](../services/pricing-service/README.md), [Gateway guide](../services/api-gateway/README.md), and [specification](specs/phase-7-pricing-administration.md).
