# Transactional Outbox — Quote Service

Quote outbox was introduced in Phase 16 and extended for QuoteExpired in Phase 18. Phase 19 adds a service-local Auth outbox publisher for UserRegistered. Phase 20 adds pricing-rule mutation intents and a Pricing publisher. Notification and Audit retain Phase 15 idempotency.

## Why it is needed

Phase 12 committed the quote and then sent an in-memory event to Kafka. A crash between these operations could permanently lose the event. Quote now stores both the complete quote graph and a durable event intent in one PostgreSQL transaction. No Kafka operation is part of the request path.

```text
HTTP Create Quote
      ↓
PostgreSQL transaction
      ├── Quote + driver/vehicle/pricing snapshots
      └── Complete QuotePriced envelope in outbox_events (PENDING)
      ↓
COMMIT
      ↓
HTTP 201

later:
Scheduled Outbox Publisher
      ↓
Kafka QuotePriced acknowledgment
      ↓
mark outbox PUBLISHED

Kafka independently delivers to Notification + Audit
      ↓
idempotent processing in each consumer database
```

Consumers can process an acknowledged event before the publisher finishes its PUBLISHED update. The diagram does not imply a transaction or ordering guarantee between that update and consumer processing.

HTTP 201 means **quote and event intent are durably committed**, not that Kafka or either consumer has finished. If Pricing fails, no quote transaction starts. If any quote/snapshot/outbox write or the database commit fails, everything rolls back. GET requests never create outbox rows.

## Stored event

`QuotePricedEventFactory` generates the event ID once, captures the original UTC occurrence time and request correlation ID, and uses the existing persisted response snapshot. `QuotePersistenceService` serializes the complete envelope with the application JsonMapper and saves an `OutboxEvent` through `OutboxRepository` in its existing `@Transactional` method.

The row stores an independent outbox ID, event ID, aggregate type `Quote`, quote UUID string as aggregate ID, event type `QuotePriced`, version 1, complete JSONB envelope, correlation ID, `PENDING`, retry count 0, and occurrence time as created_at. published_at starts null.

PostgreSQL JSONB normalizes object ordering/whitespace; logical JSON content is preserved. The publisher reads `payload::text` and sends that exact stored representation with Kafka StringSerializer, without double encoding or reconstructing business data. BigDecimal premiums remain JSON numbers; instants remain UTC strings. Kafka type headers are absent.

Event ID, payload, aggregate ID, correlation ID, and created_at never change during publication. Retry sends use the same envelope, including its original occurredAt. Only published_at records publication time.

The Phase 3 outbox table is reused. One necessary migration, Quote V6, widens correlation_id from 100 to 128 characters to match the already-approved Gateway/event contract. Existing migrations are unchanged. No new table, claim/status column, seed edit, or historical event backfill is added.

## Polling and concurrency

`QuoteOutboxPublisher` polls on a fixed delay. `OutboxPollingRepository` borrows one dedicated JDBC connection and obtains the PostgreSQL **session advisory lock `(741016, 1)`** for this quote database. All Quote instances use the same key. A competing poller skips the cycle.

The lock owner reads a bounded batch of PENDING rows ordered by created_at, then id. Its connection uses autocommit: SELECT and UPDATE statements are individual short transactions. **No database transaction remains open while waiting for Kafka.** The session connection and advisory lock remain held until the batch finishes. The success/failure updates use that same connection.

This is an intentional alternative to holding `FOR UPDATE SKIP LOCKED` row locks during network calls. Releasing those locks before sending, without durable claim fields, would allow concurrent publishers to send the same row. A database-wide advisory lock avoids that race with the existing schema and two statuses. It serializes dispatch across instances; it does not distribute batches between workers. One pool connection is occupied during a batch. Use direct PostgreSQL or session pooling; transaction-pooling proxies do not preserve session-lock ownership.

The lock is explicitly released in finally/close. If unlock fails, the connection is aborted before returning it to the pool. PostgreSQL also releases session locks when the session ends. A process crash or lost database session during an in-flight Kafka operation can still yield duplicate delivery; this is not a distributed exactly-once guarantee.

## Publication and failures

Topic remains `insurance.quote.events` (configurable); record key remains the quote UUID string. Kafka acknowledges with `acks=all` before the publisher changes status to PUBLISHED and sets published_at using the UTC Clock.

On a synchronous send failure or failed acknowledgment, status stays PENDING and retry_count increments atomically with `retry_count = retry_count + 1`. The poll ends, and a later fixed-delay poll attempts pending work again. retry_count counts failed **application send attempts**, not individual Kafka client's internal retries. It is not reset on success. An interrupted poll or failed status update does not increment this counter. If updating the counter itself fails, the intent still remains pending.

Kafka waits are bounded by max.block.ms=1000 and delivery.timeout.ms=5000 (request.timeout.ms=1000). Kafka's built-in client retries/idempotence remain enabled by its defaults. There is no application backoff policy, retry tier, cutoff, FAILED/DEAD state, or DLQ. A persistently unpublishable oldest event can delay later rows; that limitation is deferred to later retry/DLQ work.

The important duplicate window remains:

1. Kafka accepts a record.
2. The process crashes or the PUBLISHED database update fails.
3. The row stays PENDING.
4. A later poll sends the same event ID/payload again.

A send timeout can also have an ambiguous outcome: the broker may accept a record even though the client did not receive acknowledgment in time. Notification and Audit use `(event_id, consumer_name)` markers and atomic local transactions to skip duplicate business effects. **Transactional Outbox + idempotent consumers provides reliable at-least-once processing**, subject to eventual recovery and retained data. It does not provide exactly-once Kafka delivery or a global database/Kafka transaction.

## Configuration and operation

| Environment variable | Property | Default |
| --- | --- | --- |
| `OUTBOX_PUBLISHER_ENABLED` | `outbox.publisher.enabled` | true in local; false otherwise |
| `OUTBOX_POLL_INTERVAL` | `outbox.publisher.poll-interval` | 2000 milliseconds, fixed delay and initial delay |
| `OUTBOX_BATCH_SIZE` | `outbox.publisher.batch-size` | 50; valid range 1–500 |
| `QUOTE_EVENTS_TOPIC` | `quote.events.topic` | insurance.quote.events |
| `KAFKA_BOOTSTRAP_SERVERS` | `spring.kafka.bootstrap-servers` | localhost:29092 in local |

Start normally with `./mvnw -pl services/quote-service spring-boot:run`. To pause dispatch while still persisting quote intents, start with `OUTBOX_PUBLISHER_ENABLED=false`. Restart with it enabled to drain pending rows. No launcher script is required. Non-local deployments must explicitly enable the publisher.

Normal tests explicitly disable scheduling and require no broker. PostgreSQL tests call the poller directly; Kafka tests intentionally enable scheduling. Logs distinguish successful publication, failed sends, and poll/database failures using bounded metadata; empty/contended polls log only at DEBUG. Payloads and exception text are not logged by the publisher. The default Kafka error listener is suppressed, and PostgreSQL error details are disabled for Quote's JDBC connections so rejected-row errors cannot print the stored envelope.

Useful read-only checks in quote_db:

```sql
SELECT id, event_id, aggregate_id, status, retry_count, created_at, published_at
FROM outbox_events ORDER BY created_at DESC;

SELECT status, count(*) FROM outbox_events GROUP BY status;
```

Do not manually mark pending events PUBLISHED to clear a backlog: that would discard event intent. No retention or cleanup job is implemented.

See [specification](specs/phase-16-transactional-outbox.md) and [validation](phase-16-validation.md). Phase 17 now adds consumer retry/DLQ handling; the outbox publication policy described here remains unchanged. See [consumer retry/DLQ](retry-and-dlq.md).

## Phase 19 — Auth/User Events

Auth now persists UserRegistered v1 in its existing outbox during registration, then
publishes to insurance.user.events keyed by userId. Notification and Audit reuse
their idempotency and retry/DLQ paths with insurance.user.events.dlq. Auth V4 widens
outbox correlation_id to 128; consumer schemas need no migration. No historical
backfill or admin status events. See [contract and lifecycle](user-registered-event.md)
and [validation](phase-19-validation.md). Earlier phase sections are historical.

## Phase 20 — Pricing Events

Pricing admin create/update/enable/disable now commit operation-specific v1 events
in the existing outbox with the rule mutation. The service-local publisher sends to
insurance.pricing.events keyed by pricingRuleId; Audit records ADMIN/PricingRule
using existing idempotency and pricing retry/DLQ handling. Pricing V4 widens only
outbox correlation_id to 128. Calculation emits no event; Notification is unchanged.
See [contracts and lifecycle](pricing-rule-events.md), [specification](specs/phase-20-pricing-events.md),
and [validation](phase-20-validation.md). No historical backfill or Phase 21 work.

## Phase 21 — Admin Customer Management

Gateway and Auth independently authorize ADMIN customer list/detail/enable/disable.
PostgreSQL customer row locks serialize real status transitions with existing Auth
outbox inserts. Idempotent repeats create no intent. UserDisabled/UserEnabled v1
reuse the user topic/key, publisher and Audit idempotency/retry/user DLQ. Audit uses
ADMIN attribution; Notification intentionally skips status events. No migration,
backfill or token revocation: already-issued JWTs may still authorize Quote access
until expiry. See [API/security contract](admin-customer-management.md),
[event contract](user-status-events.md) and [validation](phase-21-validation.md).
Phase 22 is not implemented.
