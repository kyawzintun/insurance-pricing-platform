# Phase 9 — Quote Retrieval and Ownership Validation

Status: Complete on 2026-10-10 with Java 25.0.1. Phase 10 — Angular Foundation has not started.

## Automated Maven validation

`./mvnw -B clean verify` passed for the root aggregator and all six services: **267 tests**, zero failures/errors/skips.

| Module | Tests |
| --- | ---: |
| API Gateway | 40 |
| Auth | 19 |
| Quote | 97 |
| Pricing | 109 |
| Notification | 1 |
| Audit | 1 |

Quote adds 36 retrieval HTTP cases. They exercise missing/malformed/tampered/expired/wrong-issuer/invalid-subject/invalid-roles JWTs, unsupported authority, CUSTOMER/ADMIN detail access, identical missing/cross-customer 404s, ownership-scoped list queries, pagination/defaults/limits/invalid filters, unordered bulk-result restoration, snapshot mapping, and FAILED/EXPIRED rows with absent snapshots. Every case checks that no Pricing/catalog dependency or save method was called. The sole obsolete Phase 8 test asserting that all retrieval is forbidden was replaced by the Phase 9 tests; all creation tests remain.

Gateway adds four cases covering GET authentication and CUSTOMER/ADMIN token, path, and query forwarding. Production Gateway routing/security configuration required no changes. Existing Auth routes, admin Pricing protection, and exclusion of internal Pricing remain tested. Normal tests use mocked repositories and local HTTP stubs, with no live PostgreSQL/Kafka/Docker/H2 requirement.

The initial sandboxed test attempt could not use the JVM instrumentation/socket facilities. Rerunning with the required execution permissions passed; no application workaround was introduced.

## PostgreSQL integration validation

`./mvnw -B -pl services/quote-service -am -Ppostgres-it verify` passed with **seven integration tests** (four existing creation tests and three new retrieval tests). Testcontainers uses a disposable PostgreSQL 17.11 database and existing Flyway migrations.

Verified:

- Customer-scoped queries exclude another owner's quote; ADMIN can retrieve both owners.
- Multiple customer quotes page in created_at DESC, id DESC order, including equal creation timestamps.
- Total counts/pages remain correct with multiple adjustment rows; no duplicate quotes.
- Full driver/vehicle/pricing graph maps inside the read-only transaction, with adjustment sequence retained.
- Hibernate statement statistics show **one SELECT for detail** and **at most three SELECTs per tested list page**, including a count. Collection-fetch pagination is configured to fail in the integration test, so an unsafe pagination change cannot silently pass.
- Changing disposable catalog names after creation does not change the returned DTO. The Pricing mock fails if called during retrieval; it is never called.
- Stored FAILED/EXPIRED statuses and missing snapshots are returned safely; retrieval fingerprints prove no writes to quote, catalog, outbox, or Flyway rows.
- Existing aggregate persistence, precision, uniqueness, rollback, and Pricing-failure behavior still pass.

Catalog modifications were limited to disposable test fixtures and restored within the test. No live seed row or migration file changed.

## Live end-to-end validation

Existing Gateway, Quote, Auth, and Pricing were already running on ports 8080–8083. They were not restarted or stopped. Started newly built Gateway on **18080** and Quote on **18082**, using existing Auth (8081), Pricing (8083), PostgreSQL, and Kafka. Both temporary application processes were stopped after validation.

Registered two uniquely named fictional CUSTOMER accounts and one temporary administrator through Gateway. Only the temporary administrator was manually promoted in local Auth data and logged in again. Tokens/passwords stayed in validator memory and were not printed or committed.

Created two quotes for Customer A and one for Customer B through the new Gateway. All returned 201, stored JWT-sub ownership, and matched real Pricing. This also exercises the previously pending Phase 8 creation-through-new-Gateway flow, using temporary ports instead of interrupting the user's applications.

The following checks passed through Gateway and directly against Quote:

| Check | Result |
| --- | --- |
| No JWT on GET list/detail | 401 |
| Malformed token | 401 |
| A lists quotes | Exactly A's two quotes |
| B lists quotes | Exactly B's one quote |
| A gets own quote | 200, exact creation snapshot |
| A gets B's quote | 404 QUOTE_NOT_FOUND |
| A gets nonexistent quote | Identical 404 status/body |
| ADMIN gets A/B quotes | 200, exact snapshots |
| ADMIN lists quotes | All temporary quotes plus any pre-existing data; correct total |
| Size 1, pages 0/1/2 for A | Newest quote, older quote, empty page; totalPages 2 |
| Size 101 / customerId override | 400 |
| ADMIN missing detail | 404 |

A uniquely identified temporary VEHICLE_VALUE factor-2 pricing rule changed current calculation from **11200 THB to 22400 THB** without editing seed rows. Quote was then restarted on its temporary port with an **unreachable Pricing URL**. All GET checks still passed and returned the exact original 11200 THB snapshots. Retrieval therefore depends on neither current pricing rules nor a working Pricing Service.

## Safety and cleanup

Ordered row-content fingerprints immediately before/after all GET requests were identical across all Quote tables, Auth users, Pricing rules, and relevant outbox/Flyway tables. After removing only the three temporary quotes/children, three temporary accounts, and temporary pricing rule, fingerprints matched the original pre-validation data. Kafka insurance-topic offsets were unchanged.

No schema migration, seed file, infrastructure configuration, root dependency, or unrelated service implementation was modified. Retrieval writes no outbox records and publishes no Kafka events. Temporary validation processes were stopped; existing user processes were left running. No real credential or token was added. `git diff --check` passes.

## Implementation choices and limits

CUSTOMER ownership is included in both page/detail queries and bulk snapshot fetches, never a client filter. ADMIN access comes from validated authority; both-role tokens use ADMIN retrieval behavior. POST still requires CUSTOMER.

Only page/size are supported. Defaults 0/20, maximum size 100, fixed newest-first/ID-descending tie order. Duplicate/unknown/invalid parameters are rejected, including customerId/status/sort. Very large offsets beyond Integer.MAX_VALUE are rejected because JPA SQL offsets use int. Offset pagination can shift as new quotes are concurrently created; no frozen browsing session or cursor pagination is promised.

Detail uses an entity graph. Lists page scalar IDs, count matching rows when needed, then bulk-fetch snapshots without pagination and restore page order in Java. DTO mapping occurs inside a read-only transaction. No global eager-loading change or new index; the existing customer/creation index remains available. ADMIN listing may scan/sort more rows as data grows; no premature index was added.

Stored status is returned unchanged, including elapsed PRICED quotes. Missing optional snapshots are null, supporting existing FAILED rows without inventing their lifecycle. The Phase 8 schema limitation for rule versions/base-rule identity remains unchanged.

No editing, cancellation, deletion, repricing, draft flow, expiration processing, messaging/outbox publication, Angular, new JWT scheme, service authentication, cache, search, or export was added. Phase 9 is ready for Phase 10 planning/implementation under its own specification.

See [Quote guide](../services/quote-service/README.md) and [specification](specs/phase-9-quote-retrieval-and-ownership.md).
