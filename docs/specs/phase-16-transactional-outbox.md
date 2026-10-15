# Phase 16 — Transactional Outbox

Status: Implemented; validation results are recorded in [Phase 16 validation](../phase-16-validation.md).

## Goal and scope

Close Quote Service's database/Kafka dual-write gap for new QuotePriced v1 events. Preserve all APIs, snapshots, security, Angular behavior, event contracts, topic configuration, and Notification/Audit idempotency. Do not backfill historical quotes.

## Functional behavior

Create a quote and one PENDING outbox event in the same local transaction. Return 201 after commit without contacting Kafka. A configurable scheduled publisher sends bounded batches, marks PUBLISHED only after acknowledgment, and preserves event identity/time/correlation across repeated attempts. Kafka failure leaves durable event intent pending.

## API impact

No endpoint or JSON response changes. HTTP 201 guarantees committed quote plus outbox intent, not completed Kafka/consumer delivery. Retrieval and catalog GETs remain read-only.

## Database impact

Reuse Quote's existing outbox_events table. Map the entire envelope as JSONB. Preserve the existing graph transaction. V6 widens correlation_id to 128 to fix a real mismatch with the established 128-character contract; no other migration or seed change. Published updates change only status/published_at; failed sends increment retry_count. No Auth/Pricing outbox implementation.

## Kafka impact

Same configurable insurance.quote.events topic, quote UUID key, QuotePriced/version 1 envelope and original event ID. Replace the Phase 12 after-commit listener with polling. String serialization sends the stored JSON representation, without Java type headers. At-least-once delivery includes the acknowledged-send/unmarked-row duplicate window.

## Concurrency and transaction decisions

One PostgreSQL session advisory lock per quote database excludes competing publisher instances across a bounded batch. The dedicated connection is autocommit; no transaction spans Kafka I/O. Explicit unlock/session disposal handles normal/failure paths. This favors a small implementation without new claim columns over parallel dispatch throughput. See [design, failure boundaries and limitations](../transactional-outbox.md).

## Security considerations

JWT/ownership behavior is unchanged. Capture correlation at creation; never treat it as identity. Publish the existing compact event, not entities or tokens. Logs omit payloads and exception text; rejected PostgreSQL row details are disabled. Each service accesses only its owned logical database.

## Testing requirements

- Normal unit/HTTP regressions with scheduler disabled.
- Real PostgreSQL atomic commit/rollback, insert failures, correlation/ID/time mapping, bounded ordered queries, exclusion across connections, and immutable event data.
- Real Kafka scheduled delivery, exact stored JSON/key, broker outage with HTTP 201 and recovery, and duplicate publication after a failed PUBLISHED update.
- Local Gateway → Quote → Outbox → Kafka → Notification/Audit with one marker/business row in each consumer; preserve historical data/topics/offsets.

## Definition of Done

Direct publication removed; durable intent and bounded safe polling implemented; failures recover through later polling with stable event identity; normal Maven and separate PostgreSQL/Kafka suites pass; live evidence and operational docs are recorded. Phase 17 backoff/DLQ/cutoff, retention, CDC/Debezium, new events and exactly-once claims remain excluded.
