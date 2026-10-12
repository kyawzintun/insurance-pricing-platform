# Phase 12 — Basic QuotePriced Kafka Event Validation

Completed 2026-10-11. Scope: one direct Quote producer; no consumers/outbox/retry-DLQ implementation.

## Automated results

| Command | Result |
| --- | --- |
| `./mvnw -B clean verify` | All six modules passed; 277 normal tests, no failures/errors/skips |
| `./mvnw -B -pl services/quote-service -Ppostgres-it verify` | 12 PostgreSQL integration tests passed |
| `./mvnw -B -pl services/quote-service -Pkafka-it verify` | 1 Kafka/PostgreSQL end-to-end integration test passed |
| `git diff --check` | Passed |

The normal suite remains Docker/broker-independent. Integration profiles ran separately with disposable containers. Frontend, Gateway/Auth/Pricing/Notification/Audit production code, migrations and infrastructure configuration are unchanged; no frontend regression run was needed.

New normal tests verify stable envelope fields/version/type, unique IDs, injected UTC Clock, compact payload, correlation preservation/fallback, JSON timestamps/numeric BigDecimal, absent sensitive fields/type metadata, quote ID key, topic and safe synchronous/asynchronous failure logging. Existing HTTP tests verify event construction on successful create and none on Pricing/validation failure, retrieval or vehicle GET.

Real PostgreSQL transaction tests prove:

- Flushed rows are present inside the transaction while no Kafka send has occurred.
- Committing invokes exactly one send; outer rollback invokes none.
- A deliberately deferred foreign-key violation fails at actual commit after event registration, publishes nothing, and rolls back the quote.
- A failed asynchronous send after commit still returns HTTP 201 and leaves the complete PRICED aggregate persisted.
- A synchronous enqueue exception also leaves the quote committed.
- Existing ownership, snapshot, filtering and persistence tests still pass; outbox remains empty.

## Disposable Kafka validation

The isolated broker uses `apache/kafka:4.1.2`; PostgreSQL uses `postgres:17.11-bookworm`. The test alone creates the existing domain topic name with three partitions/replication factor one in this disposable broker, without changing shared infrastructure.

A real successful Quote creation produced exactly one JSON record with the quote UUID key. Envelope, customer identity, premium/currency, UTC occurrence time and priced/expiry timestamps matched the stored response. No type headers were present. Failed Pricing, database flush failure, rollback, detail/history and vehicle reads produced no additional records.

Two controlled extra publications for that same quote in the disposable test had distinct event IDs and the same key/partition as the initial record. The test deliberately does not hardcode the numeric partition. These are test-only publications, not application retry behavior. Its inspection client uses explicit partition assignment with auto-commit disabled and no group ID; there is no consumer service.

## Live Gateway-to-Kafka validation

Started task-owned Auth/Pricing/Quote/Gateway on free standard ports 8081/8083/8082/8080. Used the existing Compose PostgreSQL and Kafka; detected the actual published Kafka bootstrap address `127.0.0.1:29092`. Passed that address to Quote explicitly, leaving repository defaults unchanged. Downstream HTTP URLs used IPv4 loopback for local validation.

Registered/logged in a uniquely named fictional CUSTOMER. Loaded vehicle references through Gateway and created a comprehensive Toyota Camry quote through `POST /api/v1/quotes`, supplying a unique safe `X-Correlation-ID`. Creation returned 201. Inspected the record using Kafka CLI with explicit partition/offset and no offset commits.

| Evidence | Value |
| --- | --- |
| Quote ID / Kafka key | `0369b6f8-66b4-4d50-ab29-d5d8459a5e42` |
| Event ID | `b8e7c600-bbca-4708-ad82-96810a074462` |
| Correlation ID | `phase12-test-3b6f02a5d55f48309bfbd1e7ba7ff525` |
| Topic / partition / record offset | `insurance.quote.events` / 0 / 0 |
| Event type / version / aggregate / producer | `QuotePriced` / 1 / `Quote` / `quote-service` |
| Final premium | 11200.00 THB, matching the persisted quote |

`aggregateId` and payload `quoteId` matched the record key; customerId matched the temporary authenticated account. Reference, PRICED status, coverage, pricedAt and expiresAt matched the Quote response. The exact correlation ID was preserved through Gateway into Kafka. The envelope had exactly nine fields and the payload nine fields, with no personal driver data or authentication credentials.

## Offset and data safety

Captured all domain/DLQ partition offsets, topic descriptions and SQL table fingerprints before validation. After the one quote creation, **only `insurance.quote.events` partition 0 advanced from end offset 0 to 1**. All other partition offsets were unchanged. Topic names/partition counts/replication factors/configuration were unchanged. The numeric partition is observation, not a contract.

Quote history/detail and vehicle GET calls changed neither SQL fingerprints nor offsets. Outbox contents/counts, pricing rules, vehicle catalog and Flyway history stayed unchanged. Creation wrote only the existing quote snapshot tables (plus the temporary account created separately for login).

Removed the temporary quote aggregate and account using local maintenance. All captured SQL fingerprints returned to baseline. **The identifiable QuotePriced test event remains in append-only local Kafka history**, referring to the now-deleted temporary data; it was not deleted or replayed. No further offsets changed during cleanup.

All four task-started services were stopped and their ports verified free. Shared Docker infrastructure was left intact. No commit was created.

## Intentional limits

Direct after-commit send is not atomic with PostgreSQL. A process crash or producer failure can leave the quote without a Kafka event; safe logging does not recover it. HTTP 201 remains a quote-persistence result, not a Kafka delivery guarantee. Basic/default Kafka client retries/idempotence do not solve this dual-write gap. Phase 16 Transactional Outbox will address it. No outbox rows, consumer services/groups, DLQ processing or other domain events were added.

Phase 12 is complete and ready for Phase 13 — Notification Consumer. Phase 13 has not started.
