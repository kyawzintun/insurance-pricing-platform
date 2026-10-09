# Phase 8 — Quote Service MVP Validation

Status: Implementation and automated/direct-service validation complete on 2026-10-08. **Live quote creation through the updated Gateway on port 8080 is pending** the user's choice to restart the existing Gateway or use temporary ports. Phase 9 has not started.

## Maven and automated tests

`./mvnw -B clean verify` passed for the root and all six services, with **228 tests, zero failures/errors/skips**. Normal tests require no PostgreSQL, Kafka, Docker, or H2.

| Module | Tests |
| --- | ---: |
| Gateway | 36 |
| Auth | 19 |
| Quote | 62 |
| Pricing | 109 |
| Notification | 1 |
| Audit | 1 |

Quote's 61 HTTP cases use real servlet security/controller/service/client code, mocked repositories, a deterministic Clock, and an ephemeral HTTP Pricing stub. They cover CUSTOMER success, ADMIN-only denial, missing/tampered/expired/wrong-issuer/non-UUID-subject JWTs; request validation/unknown fields; active matching catalog references and DB-sourced names; correct downstream payload with no Authorization/customer/catalog IDs; HTTP failures, timeout, malformed/inconsistent pricing responses; persisted identity, metadata, snapshot values/sequence, and 30-day expiration. A separate client test proves safe connection-refusal handling. Failed requests verify no aggregate save. Retrieval endpoints remain denied.

After strengthening the unordered-adjustment case, `./mvnw -B -pl services/quote-service -am test` also passed all 62 Quote tests.

Gateway adds missing-token and authenticated root/subpath forwarding cases, preserving the exact original token and path. Existing Auth/admin routing and internal Pricing exclusion tests pass. All 109 Pricing and 19 Auth tests remain passing.

## Disposable PostgreSQL integration tests

`./mvnw -B -pl services/quote-service -am -Ppostgres-it verify` passed with **four additional integration tests** against disposable PostgreSQL 17.11. Existing Quote Flyway migrations were applied without modification.

- Complete aggregate persists with exact money precision, customer/driver/vehicle/pricing snapshots, a unique reference, and 30-day expiration. Actual database uniqueness constraints reject duplicate quote references and second drivers for one quote.
- A child-row constraint failure rolls back the entire graph; all aggregate counts remain unchanged.
- Pricing failure leaves no new rows.
- Multiple quote references are unique and later pricing results do not alter previous snapshots.

The test double asserts there is no active transaction at the Pricing call. Quote retains five successful migration records and zero outbox rows. This profile uses no live developer database.

## Live validation completed so far

Reused the existing healthy PostgreSQL/Kafka infrastructure, Auth on 8081, Pricing on 8083, and the existing Gateway on 8080. Started only a temporary Quote process on 8082, then stopped it after validation. Existing Auth/Pricing/Gateway processes were not interrupted.

Registered/logged in a uniquely named fictional CUSTOMER through Gateway. The access token/password were kept in validator memory, not printed or committed. Loaded the actual active Toyota/Camry IDs from quote_db. Created quotes **directly on port 8082**, because the already-running Gateway predates the new route.

| Check | Result |
| --- | --- |
| Gateway registration/login | 201 / 200; actual Auth-issued CUSTOMER JWT |
| No JWT at Gateway quote path | 401 |
| No JWT directly at Quote | 401 |
| Client-supplied customerId | 400 |
| CUSTOMER create directly at Quote | 201, PRICED |
| Stored customer_id | Exactly JWT sub |
| Vehicle names | Actual Toyota / Camry catalog values |
| Driver, vehicle, breakdown, every adjustment field | Match request/catalog/direct Pricing output |
| Expiration | Exactly 30 days after createdAt |
| Baseline premium | 11200 THB, matching direct Pricing calculation |
| Temporary effective VEHICLE_VALUE factor 2 | New quote and direct Pricing both 22400 THB |
| Original quote after the rule change | Premium and complete snapshots unchanged |

Only a uniquely identified temporary pricing rule was inserted for validation; no existing seed row was edited. The changed new premium demonstrates real Quote-to-Pricing integration rather than a local fallback.

## Database, Kafka, and process safety

The two temporary quotes and their children, temporary user, and temporary pricing rule were removed with narrowly scoped local maintenance. Ordered row-content fingerprints before/after cleanup matched across all Quote tables, Auth users, Pricing rules, and the relevant outbox/Flyway tables. Kafka offsets for all insurance topics were identical before/after. No schema or topic configuration changed.

Production Quote code writes only quotes, quote_drivers, quote_vehicles, pricing_breakdowns, and pricing_adjustments. It contains no outbox writer, Kafka producer, or listener. Infrastructure, existing migrations/seeds, root dependency versions, Auth/Pricing/Notification/Audit production code remain unchanged. The reused key is explicitly public and local-only; no real secret or token was added.

The temporary Quote process was stopped. No new background application remains. Existing Gateway/Auth/Pricing processes belong to the pre-validation session and were left untouched.

## Remaining live check

The Gateway route is covered by passing automated forwarding tests, but **201 quote creation through the newly built Gateway on port 8080 has not yet been executed**. Restarting that existing user process requires the pending choice; alternatively, the user may choose temporary ports. Do not treat the Phase 8 end-to-end validation checklist as fully complete until this check passes.

## Choices and deferred work

- CUSTOMER authority is required; ADMIN-only cannot create quotes.
- Reference format is `Q-yyyyMMdd-<32 uppercase UUID hex digits>` (43 characters), backed by the existing unique constraint.
- UTC Clock controls request date checks and quote metadata; database timestamp precision is microseconds. Expiry is stored, not automatically processed.
- Catalog validation is point-in-time; no database transaction is held across the remote call.
- RestClient uses JDK HTTP transport, default 2s connect / 5s read timeouts, no retries, and no fallback calculations. Downstream HTTP/response errors become safe 502; connection/timeout failures become 503.
- Failed Pricing creates no partial or FAILED quote. The separate persistence transaction commits the complete graph or rolls it back.
- The existing schema cannot preserve basePricingRuleId, basePricingRuleVersion, or adjustment pricingRuleVersion. Quote stores every supported snapshot field and intentionally omits those unsupported fields from its response. No migration was added merely for these columns.
- Quote → Pricing is synchronous internal REST without service credentials or a forwarded user token. Service-to-service authentication remains deferred to Phase 25.
- No retrieval/history/ownership retrieval, editing, repricing, drafts, expiration job, Kafka/outbox events, UI, discovery, cache, refresh tokens, or Phase 9 implementation.

See [Quote service guide](../services/quote-service/README.md) and [Phase 8 specification](specs/phase-8-quote-service-mvp.md).
