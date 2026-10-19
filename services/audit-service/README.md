# Audit Service — through Phase 17

Consumes the existing QuotePriced v1 envelope and persists one audit record. Audit uses its own `audit-service` Kafka consumer group, independent of Notification's `notification-service` group. Each group receives the same record and tracks its own progress.

```text
                    insurance.quote.events
                           |
             +-------------+-------------+
             |                           |
             v                           v
 notification-service group      audit-service group
             |                           |
             v                           v
   notification_db                audit_db
```

## Local startup

From the repository root, with shared Docker infrastructure running:

```bash
./mvnw -pl services/audit-service spring-boot:run
```

Defaults match the other local services: `local` profile, HTTP port 8085, PostgreSQL `localhost:15432/audit_db`, Kafka `localhost:29092`. Environment overrides remain available. Maven does not automatically read Compose's `.env`. Stop the application with Ctrl+C. No new HTTP API or security behavior is added; `/actuator/health` remains public, other application paths are denied.

| Configuration | Default |
| --- | --- |
| `QUOTE_EVENTS_TOPIC` | `insurance.quote.events` |
| `AUDIT_CONSUMER_GROUP` | `audit-service` |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` |
| `spring.kafka.consumer.enable-auto-commit` | `false` |
| `spring.kafka.listener.ack-mode` | `record` |
| Listener concurrency | 1 |

Keep the group different from Notification's group. Same-group consumers divide partitions; different groups independently read all partitions. The existing topic has three partitions and local replication factor one. No topic or broker setting is changed.

`earliest` matters when a partition has no usable committed offset. A new Audit group may consume Phase 12/13 historical events referencing deleted temporary Quote/Auth rows. This is expected: the event supplies the audit evidence; Audit makes no HTTP/database lookup into another service. Restarting an existing group resumes from its committed offsets, not from the beginning merely because earliest is configured.

## Contract and processing

`QuotePricedEnvelope` and `QuotePricedData` are consumer-owned DTOs matching the [unchanged Phase 12 contract](../../docs/quote-priced-event.md). They are not a new event format. Kafka retains raw key/value bytes; `QuoteEventDecoder` explicitly maps to the DTO with Jackson. No Java type headers, wildcard trusted packages, polymorphic typing, JPA deserialization, or generic routing framework is used.

The decoder validates required IDs/timestamps, supported version 1, event/aggregate/producer names, safe correlation ID, aggregateId == data.quoteId, customer ID, reference, PRICED status, coverage, nonnegative BigDecimal premium, THB currency, and priced/expiry dates. It preserves decimal precision and rejects trailing JSON. Separate envelope/payload validation methods make the checks easier to read. An absent/mismatched key is logged diagnostically without echoing the key; the validated payload is authoritative.

The listener calls a separate transactional `AuditService.create()` bean. That service builds an allowlisted metadata DTO, inserts/flushes the audit row, and commits through Spring's transaction proxy before control returns to the listener. Only successful listener completion allows RECORD mode to commit the next Kafka offset. No manual acknowledgment, Kafka transaction, XA/2PC, or database/Kafka atomicity is claimed.

## Audit mapping

| Column | Source |
| --- | --- |
| `id` | New UUID |
| `event_id` / `event_type` | Envelope eventId / QuotePriced |
| `actor_type` / `actor_id` | CUSTOMER / data.customerId |
| `entity_type` / `entity_id` | Quote / data.quoteId |
| `correlation_id` | Complete envelope correlationId |
| `occurred_at` | Envelope occurredAt, PostgreSQL microsecond precision |
| `producer` | Envelope producer (quote-service) |
| `created_at` | Injected Clock processing instant, UTC/microsecond precision |
| `metadata` | Compact seven-field JSONB object below |

Actor attribution is based on the event's customerId and the current CUSTOMER quote flow; Audit does not independently authenticate an actor or resolve their identity. Actor/entity IDs remain strings containing UUIDs, as required by the existing schema.

```json
{
  "quoteReference": "Q-EXAMPLE",
  "status": "PRICED",
  "coverageType": "COMPREHENSIVE",
  "finalPremium": 11200.00,
  "currency": "THB",
  "pricedAt": "2026-10-13T10:00:00Z",
  "expiresAt": "2026-11-12T10:00:00Z"
}
```

`AuditMetadata` explicitly limits persisted context to these fields. Money remains a JSON number. No full driver data, JWT, Authorization header, password, or signing key is stored. A serialized DTO is mapped to PostgreSQL JSONB using Hibernate's JSON JDBC type; the database stores an object, not an escaped JSON string.

### Necessary schema compatibility fix

Phase 3 defined `correlation_id VARCHAR(100)`, but Gateway and the Phase 12 contract allow 128 characters. Rejecting or truncating an otherwise-valid event would violate that contract. The service-owned **V3__widen_audit_correlation_id.sql** widens only this column to VARCHAR(128). Existing V1/V2 files and their Flyway history/checksums are preserved; Flyway adds the normal V3 history entry. No table redesign, new table, or uniqueness change is made. Integration tests prove a full 128-character ID survives.

## Error and duplicate-delivery policy

| Condition | Behavior |
| --- | --- |
| New valid QuotePriced v1 | Commit marker and audit row together, then advance Kafka progress |
| Duplicate eventId | Return DUPLICATE successfully without another business insert |
| Unsupported nonempty event type | Intentionally log/skip and advance its offset |
| Unsupported QuotePriced version, malformed JSON or invalid data | Direct acknowledged DLQ without processing retries |
| Genuine database failure | Bounded retries, then acknowledged DLQ |

Spring Kafka's `DefaultErrorHandler` now provides fixed-backoff retries and acknowledged DLQ recovery. A failed DLQ send leaves the original source record replayable. Notification and Audit groups advance independently. See Phase 17 below; never reset offsets to hide failed records.

The listener logs fixed reason codes, topic/partition/offset and validated event/quote/correlation metadata. It does not log payloads or nested exception messages; no stack traces are added to DLQ headers.

**Phase 15 makes duplicate delivery idempotent** using the existing local processed_events table. Both consumers now treat committed duplicates as successful processing. See the atomicity, concurrency and historical-row compatibility details below.

## Tests and learning order

```bash
# No Docker required:
./mvnw -pl services/audit-service test
./mvnw clean verify
# Disposable infrastructure; Docker required; run separately:
./mvnw -pl services/audit-service -Ppostgres-it verify
./mvnw -pl services/audit-service -Pkafka-it verify
```

Read event DTOs → `QuoteEventDecoder` → `QuoteAuditListener` → `AuditService` → `AuditMetadata` / `AuditRecord` / `AuditRecordRepository` → `AuditKafkaConfiguration` and YAML. Then read the normal mapping/validation tests, PostgreSQL tests and Kafka test.

PostgreSQL tests verify mapping, JSONB, correlation compatibility, atomic markers, duplicate success, rollback, historical rows and concurrency. Kafka tests verify real same-offset redelivery after a simulated post-DB-commit failure, a separate duplicate record, preserved timestamps, unsupported-type skipping and malformed-input DLQ recovery.

Inspect the two independent groups:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group notification-service

docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group audit-service
```

Committed offsets identify the next record to read. Each group should independently catch up to topic end offsets; empty partitions may display no committed offset (`-`). Consumer reads do not change topic end offsets. Kafka records and group offsets remain durable during ordinary SQL cleanup.

See [Phase 14 specification](../../docs/specs/phase-14-audit-consumer.md) and [validation evidence](../../docs/phase-14-validation.md). Phase 15 is documented below.

## Phase 15 — Idempotent processing

Kafka delivery is at least once: a database commit can succeed before the consumer commits its Kafka offset. A crash in that gap redelivers the same record. Duplicate delivery is now successful processing, not a unique-key failure.

```text
Kafka event (validated)
    ↓
BEGIN local PostgreSQL transaction
    ↓
INSERT processed_events ON CONFLICT (event_id, consumer_name) DO NOTHING
    |
    +-- conflict → DUPLICATE → commit/return → Kafka offset may advance
    |
    +-- inserted
           ↓
       existing business row? → yes: legacy DUPLICATE, keep new marker
           |
           no
           ↓
       insert business row
           ↓
       COMMIT marker + business row together
           ↓
       PROCESSED → Kafka offset may advance
```

`ProcessedEventRepository.tryMarkProcessed()` executes a parameterized native insert using the transaction-bound JPA EntityManager. Its MANDATORY propagation rejects calls without an existing transaction. The separate service's `@Transactional create()` owns the transaction; the marker never commits independently from the business operation. Failure rolls back both. RECORD mode still commits progress only after the service proxy returns successfully.

Deduplication identity is `(eventId, consumerName)`. The stable logical consumer name is **audit-service**, independent of instance IDs, hostnames and configurable Kafka group IDs. Changing the Kafka group does not change this logical identity. Notification and Audit use separate local databases and names, so each legitimately processes the same event once.

PostgreSQL's existing composite primary key chooses the winner. Concurrent duplicate attempts wait for the first transaction: after its commit the loser gets a no-op and skips business work; after its rollback another attempt may insert the marker and process normally. No SELECT-first race, in-memory lock, distributed lock or shared database is used. Business UNIQUE(event_id) constraints remain defense-in-depth.

For a normal new event, `processed_at` is the same injected UTC/microsecond processing instant used for the business row's creation time. Duplicate conflict handling does not update the marker or business row. For a pre-Phase-15 business row lacking a marker, the service first wins the atomic marker claim, then checks `existsByEventId`. It returns DUPLICATE and commits the new marker without rewriting the historical row. That marker's time is the first tracked replay time, not a reconstructed historical processing time. There is no startup scan or automatic bulk backfill, and no attempt to catch a unique violation inside an already-aborted PostgreSQL transaction.

The listener logs `result=PROCESSED` or `result=DUPLICATE` at INFO with safe event/type/consumer/quote/correlation/partition/offset metadata. Unsupported event types are still intentionally skipped without markers. Invalid supported events now go directly to DLQ; genuine processing failures use bounded retries first. Neither is converted into duplicate success. The Phase 15 business transaction and deduplication semantics remain unchanged.

Tests prove first/duplicate/different-event behavior, unchanged timestamps, marker and business rollback, stable names, lazy historical compatibility, concurrent commit and rollback outcomes. The Kafka test injects a test-only failure in the listener **after the service transaction commits but before the listener returns**, then restarts the same group. Both listener invocations receive partition 0 offset 0: true same-record redelivery, without offset resets or republishing. A separate second Kafka record carrying the same eventId also succeeds. Phase 17 now sends invalid JSON to DLQ before advancing past it. See [Phase 15 specification](../../docs/specs/phase-15-idempotent-consumers.md) and [validation](../../docs/phase-15-validation.md).

## Phase 17 — Retry and DLQ

`DefaultErrorHandler` uses fixed backoff with `KAFKA_CONSUMER_MAX_ATTEMPTS=3` total attempts and `KAFKA_CONSUMER_BACKOFF_MS=1000`. Processing/database failures retry; malformed UTF-8/JSON, unsupported versions and invalid supported data go directly to DLQ. Unknown nonempty event types remain intentional skips. DUPLICATE remains successful and bypasses retry/DLQ.

`QuoteDeadLetterRecoverer` is a framework recovery callback, not a retry loop. It publishes unchanged key/value bytes to the existing **insurance.quote.events.dlq**, preserving source partition (both topics have three partitions). Fresh headers identify the original topic/partition/offset/key, failing consumer service/group, safe exception class/reason, failure time, attempt count, and readable event/correlation IDs. No stack traces or incoming authorization headers are copied.

Source offsets advance after business success or DLQ acknowledgment. If DLQ publication fails, recovery throws and the original record stays replayable; later bounded processing/recovery cycles can continue. DLQ send and source commit are not atomic, so duplicate DLQ publication remains possible. No automatic replay is provided.

Consumer attempts are separate from Quote's outbox retry_count. No schemas, idempotency services/repositories, outbox semantics, topics or partition counts changed. There are no retry topics, DLQ consumer, new business events, or Phase 18 changes.

For learning, read the Kafka configuration → listener's two exception categories → `QuoteDeadLetterRecoverer` → retry unit tests → Kafka integration test. See [policy, headers, acknowledgment details and inspection commands](../../docs/retry-and-dlq.md), [specification](../../docs/specs/phase-17-retry-and-dlq.md), and [validation](../../docs/phase-17-validation.md).

## Phase 18 — Quote Expiration

Automatic PRICED → EXPIRED transitions use bounded PostgreSQL transactions and the existing outbox. Notification supports QUOTE_EXPIRED; Audit uses a SYSTEM actor. See the [Phase 18 specification](../../docs/specs/phase-18-quote-expiration.md) for configuration, contract, concurrency and failure semantics. Phase 19 is outside this change.

## Phase 19 — UserRegistered

See the [UserRegistered v1 contract and operational configuration](../../docs/user-registered-event.md)
and [Phase 19 validation](../../docs/phase-19-validation.md). Registration commits user +
outbox intent atomically; the Auth publisher later sends stored JSON to
insurance.user.events keyed by userId. Notification creates a simulated
USER_REGISTERED / CREATED row with null quote_id. Audit records CUSTOMER/user UUID
with User entity and role/status metadata, excluding email. Both consumers reuse
processed_events, bounded retries and insurance.user.events.dlq. Unknown types skip.
No historical user backfill, admin status events or Phase 20 work is included.

User event topics are configured with `USER_EVENTS_TOPIC` (default
`insurance.user.events`) and `USER_EVENTS_DLQ_TOPIC` (default
`insurance.user.events.dlq`). Listener and DLQ routing use these same settings.
Provision overridden topics with matching source/DLQ partition counts before use.

## Phase 20 — Pricing events

PricingRuleCreated/Updated/Enabled/Disabled v1 use the existing transactional outbox
and Audit idempotency/retry design. Authenticated ADMIN UUID, request correlation
and the resulting rule snapshot are recorded; the pricingRuleId is the Kafka key.
`PRICING_EVENTS_TOPIC` defaults to insurance.pricing.events in Pricing/Audit;
`PRICING_EVENTS_DLQ_TOPIC` defaults to insurance.pricing.events.dlq in Audit.
Pricing publisher settings are `PRICING_OUTBOX_PUBLISHER_ENABLED` (true locally,
false otherwise), `PRICING_OUTBOX_POLL_INTERVAL` (2000 ms) and
`PRICING_OUTBOX_BATCH_SIZE` (50, valid 1–500). No Kafka wait in HTTP handling.
Only Pricing V4 widens outbox correlation IDs to 128; existing migrations stay intact.
No pricing-calculation events, customer notifications or historical backfill.
See [contract/configuration](../../docs/pricing-rule-events.md) and
[validation](../../docs/phase-20-validation.md). Phase 21 is not implemented.
