# Phase 16 — Transactional Outbox validation

Date: 2026-10-14. Scope: QuotePriced outbox in Quote Service only. No Phase 17 implementation. See [specification](specs/phase-16-transactional-outbox.md) and [design](transactional-outbox.md).

## Implementation inventory

New production files under `services/quote-service/src/main/`:

- `java/com/insurance/platform/quote/entity/OutboxEvent.java`: existing table mapping with full envelope JSONB and immutable event fields.
- `java/com/insurance/platform/quote/repository/OutboxRepository.java`: transactional persistence.
- `java/com/insurance/platform/quote/repository/OutboxPollingRepository.java`: bounded PENDING query, session advisory lock, short operational updates and lock cleanup.
- `java/com/insurance/platform/quote/service/QuoteOutboxPublisher.java`: acknowledged sends and safe operational logging.
- `java/com/insurance/platform/quote/config/OutboxSchedulingConfiguration.java`: conditional scheduling.
- `resources/db/migration/V6__widen_outbox_correlation_id.sql`: necessary 100 → 128 character compatibility fix.

Modified QuotePersistenceService to save the event envelope in its existing transaction, removed QuoteEventPublisher and its AFTER_COMMIT path, clarified QuotePricedEvent's factory-result role, and updated Kafka/local configuration. Existing event factory and wire records retain their semantics. New publisher unit tests and revised transaction/Kafka/HTTP tests cover the new path. Normal test configuration disables the scheduler.

Docs updated: root/Quote READMEs, architecture, context, database, Kafka, event contract, security, roadmap; added transactional-outbox design, Phase 16 specification, and this validation record. No Notification/Audit implementation, frontend, Gateway, Auth/Pricing implementation, topic configuration, or old migration edits.

## Automated checks

Run from the root, with integration profiles separately:

```bash
./mvnw -B clean verify
./mvnw -B -pl services/quote-service -Ppostgres-it verify
./mvnw -B -pl services/quote-service -Pkafka-it verify
```

Final validation completed successfully on 2026-10-14 and reviewed on 2026-10-15:

| Check | Result |
| --- | --- |
| Full Maven reactor, all six services | BUILD SUCCESS; 341 tests, zero failures/errors/skips |
| Quote normal tests (included above) | 112 passed |
| Quote PostgreSQL integration profile | 17 passed, zero failures/errors/skips |
| Quote Kafka integration profile | 1 combined lifecycle test passed, zero failures/errors/skips |
| Live end-to-end | HTTP 201 → PENDING → PUBLISHED → one notification and one audit, with markers |
| Git whitespace check | Passed |

Phase 16 is complete and ready for Phase 17. Phase 17 has not started.

Unit/HTTP coverage includes existing JWT, ownership, validation, pricing-client and retrieval behavior; scheduler isolation; no direct Kafka call from creation; exact topic/key/payload; acknowledgment-before-marking; asynchronous and synchronous send failure; failed mark handling; empty/contended polls; batch bounds; and metadata-only failure logs.

PostgreSQL coverage includes all quote graph/snapshot/retrieval regressions plus:

- Successful graph + exactly one PENDING envelope, matching event ID/quote ID, original occurrence and 128-character correlation.
- Quote child failure, forced outbox insert failure, outer rollback and deferred commit failure leave no partial durable graph/event intent.
- HTTP 201 without invoking Kafka, even if a Kafka mock would fail.
- Oldest-first bounded queries and exclusion on a second connection/thread while the first session holds the lock.
- `pg_stat_activity` confirms the advisory-lock owner has no open database transaction during the claim period.
- Failure count increment, successful PUBLISHED timestamp, immutable stored payload/event ID/created_at, and skipping already published rows.
- Kafka acknowledgment followed by forced status-update failure leaves PENDING; next poll sends exactly the same stored JSON.
- PostgreSQL rejected-row details do not leak the event payload into application exception messages.

Kafka integration uses disposable PostgreSQL and Kafka and tests one combined lifecycle:

1. Quote/outbox created inside a transaction; an independent publisher sees no uncommitted intent.
2. Actual scheduled polling publishes exactly one normal record, with quote UUID key, no type headers, full unchanged v1 contract, and bytes matching stored `payload::text`.
3. Pause the disposable broker. HTTP POST still returns 201 and commits quote/PENDING outbox. A failed background send increments retry_count.
4. Resume the same broker. Scheduled polling eventually marks that same row PUBLISHED, preserving its entire payload/event ID/correlation/time. A timed-out send can arrive after recovery, so all recovered records are checked for identical identity/payload rather than assuming exactly one.
5. A disposable CHECK constraint blocks the PUBLISHED update after a successful real send. Remove the constraint and poll again: two Kafka records carry identical JSON/event ID and quote key on the same partition. Already published rows produce no further records.

Phase 15 consumer idempotency implementation is unchanged; its normal regression tests remain part of the full build. No exactly-once claim is made.

## Live end-to-end evidence

Shared PostgreSQL and Kafka were already healthy. All six application ports were free. Started six task-owned services on normal ports 8080–8085; Quote's polling interval was 10000 ms for observation, with publisher enabled. Captured baseline table fingerprints, historical row hashes, topic definitions, end offsets and both durable consumer group positions. Initial quote_db.outbox_events count was zero; no backfill.

Created one fictional CUSTOMER through Gateway registration/login, then POSTed one quote through Gateway. For a deterministic initial observation, a validation-only PostgreSQL session briefly held the same advisory lock while the publisher remained enabled. HTTP returned 201 with both the quote and exactly one PENDING row committed, retry_count 0 and published_at null. Released the lock; normal scheduled polling completed publication.

| Evidence | Value |
| --- | --- |
| Quote / Kafka key | `e5da8895-1068-47c1-ac4a-216577b28b69` |
| Outbox ID | `1d90f6d9-31de-413e-94f5-920ad2086aa6` |
| Event ID | `57428a09-e354-442e-83eb-a1da365de486` |
| Initial status | PENDING |
| Final status / retry_count | PUBLISHED / 0 |
| Original created_at | `2026-10-14T12:17:44.970711Z` |
| published_at | `2026-10-14T12:17:51.454723Z` |
| Topic / partition / record offset | insurance.quote.events / 1 / 4 |
| Topic end offsets, before → after | partition 0: 2 → 2; partition 1: 4 → 5; partition 2: 1 → 1 |

Manual Kafka inspection used a fixed partition/offset and no group commits. Event JSON matched the stored envelope and original request correlation. Both consumers produced exactly one business row and one processed_events marker for this event, with their distinct stable logical consumer names. Both groups finished at offsets 2/5/1 with lag zero on all partitions.

The shared broker was never stopped or paused. Broker outage/recovery and deliberate duplicate publication were exercised only in disposable integration infrastructure.

## Safety and process cleanup

All historical application rows were preserved, verified by row hashes captured before creating the validation fixture. Pricing rules, vehicle seeds, Auth/Pricing outboxes and other Flyway histories were unchanged. Quote Flyway applied only the new V6 migration; prior migration files/checksums were not edited. Topic definitions and unrelated topic end offsets were unchanged. No group offset reset, broker-data deletion, backfill or consumer logic change occurred.

The single fictional account, quote graph, PUBLISHED outbox and corresponding consumer rows/markers remain as inspectable validation data. No manual writes or cleanup deletes were made to Notification/Audit databases; their consumers alone created those rows. The one Kafka record and naturally advanced offsets remain. No credentials or access tokens were saved in the repository or documentation.

All six task-owned application process groups were stopped after validation; ports 8080–8085 were confirmed free. Shared PostgreSQL/Kafka remain running. No existing user service process needed interruption.

## Limits and deferred work

Single active publisher per quote database; one connection occupied while sending; session-pooling requirement; at-least-once duplicate window; no strict global event ordering across quotes. Persistent failures can delay the backlog. Fixed-delay polling is the only application resubmission mechanism; retry_count records failed send attempts, not Kafka's internal retries. No backoff policy, max cutoff, DLQ, retention job, CDC, Debezium, new domain events, Auth/Pricing outbox, or Phase 17 code.
