# Phase 6 — Pricing Service MVP Validation

Status: Complete. Validated on 2026-10-07 with Java 25.0.1. Phase 7 — Pricing Administration has not started.

## Build and tests

Final `./mvnw -B clean verify`: **BUILD SUCCESS** for all six services and the root aggregator. **108 tests passed**, zero failures/errors/skips:

| Module | Tests |
| --- | ---: |
| API Gateway | 24 |
| Auth | 19 |
| Quote | 1 |
| Pricing | 62 |
| Notification | 1 |
| Audit | 1 |

Pricing's 39 unit cases use a fixed UTC Clock, Jakarta validation, and a mocked repository. They cover base-only pricing, birthday/age boundaries, experience, vehicle age/value, claims, both coverage types, all six numeric operators, inclusive BETWEEN endpoints, deterministic multi-factor order and continuous breakdown, latest-rule/UUID tie-break, disabled/future rules, missing and nonpositive base, malformed numeric configuration, reversed ranges, unsupported coverage operators, missing factors, HALF_UP per-step rounding, and monetary overflow.

The 23 real HTTP cases run controller, service, validation, and security with a mocked repository and fixed Clock. They cover health, temporarily public internal POST, response DTOs, invalid/missing/malformed/unknown input, client-supplied age/IDs, enum numbers, fractional integer input, missing configuration, and safe database errors. Normal Maven tests need no running PostgreSQL/Kafka, Docker, or H2. Repository SQL/JPA behavior is validated separately against actual PostgreSQL below.

During development, a strict-enum setting initially used Jackson 2's property location. It was corrected to Jackson 3's `spring.jackson.datatype.enum.fail-on-numbers-for-enums`; the final HTTP suite and full build passed.

## Live PostgreSQL validation

Used existing healthy PostgreSQL/Kafka infrastructure with Pricing on **8083**, default local profile, and PostgreSQL on **15432**. Started the newly packaged Pricing jar, checked health UP, and POSTed to `/internal/v1/pricing/calculate`.

Baseline: age 31, experience 8 years, vehicle age 5, value 700000 THB, engine size 1500 cc, one claim, comprehensive coverage. Variations use the unchanged Phase 3 seeds:

| Case | Expected and actual final premium |
| --- | ---: |
| Experienced driver, comprehensive | 11200.00 THB |
| Young driver, age 23 | 14000.00 THB |
| Old vehicle, age 11 | 13440.00 THB |
| Two previous claims | 14560.00 THB |
| Third-party coverage | 8000.00 THB |
| Age 23, experience 2, vehicle age 11, two claims, comprehensive | 25116.00 THB |

Each response contained base rule ID/version, applied rule IDs/versions, input values, factors, sequential before/after amounts, currency, and timestamp. Repeated calls returned identical premiums and breakdowns, excluding the expected changing calculatedAt timestamp. Actual JPA mapping, enum/BigDecimal conversion, and the active-rule SQL query were exercised.

Added two uniquely identified temporary VEHICLE_VALUE fixtures without editing seeds: a disabled past rule and an enabled future rule, each with factor 9 that would visibly alter the result if applied. Neither appeared in adjustments or affected the premium. Both fixtures were deleted after validation; the original database fingerprint was restored.

Invalid value, birth date today, future manufacturing year, unknown/numeric coverage, and fractional driving years returned safe 400 responses. The final rebuilt jar was checked after the Jackson configuration correction.

## Database and Kafka safety

Compared ordered content fingerprints of all pricing_rules rows, all outbox_events rows, and Flyway history before/after calculations. They were unchanged, both with the original seeds and while fixtures were present. Fixture setup/cleanup were separate validation operations; calculation itself made no writes. No seed or migration file changed. Outbox row count/content stayed unchanged. Kafka topic offsets before/after validation were identical.

Production calculation is annotated `@Transactional(readOnly = true)`. The repository exposes only the enabled/effective query, with no save/delete methods. No KafkaTemplate, producer, listener, or outbox writer was added. No Gateway route, Quote integration, Maven version, infrastructure configuration, or other service production code changed. No credentials or secrets were added. `git diff --check` passes.

The temporary Pricing process was stopped after validation. Existing infrastructure and other applications were left running.

## Boundaries and assumptions

One newest-effective matching rule per category, UUID text ascending tie-break; category order and per-step HALF_UP rounding are documented in the [service guide](../services/pricing-service/README.md). UTC time is captured once per calculation. Manufacturing year lower bound is 1886; vehicle value accepts up to 13 integer/two fractional digits. Base must be positive; factors may be zero. Engine size is validated but unused because no supported rule type uses it. These are educational MVP choices.

The internal endpoint is temporarily unauthenticated and stays off Gateway. Service-to-service authentication is deferred to Phase 25; this is not production-secure. No pricing-admin CRUD, Quote persistence/integration, Angular, Kafka messaging, outbox publishing, retry/DLQ, caching, or rule-engine framework was implemented. Ready for Phase 7 after its specification is supplied; Phase 7 has not started.
