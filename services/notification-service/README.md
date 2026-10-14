# Notification Service — through Phase 15

The first application Kafka consumer. A new valid QuotePriced v1 event creates a simulated notification in `notification_db.notifications`; there is no email/SMS integration or notification REST API.

```text
Quote Service → insurance.quote.events → notification-service group
             → Notification Service → notification_db.notifications
```

## Run locally

Start shared infrastructure with `docker compose up -d`. For this machine's PostgreSQL 15432 and Kafka 29092 mappings, run from the repository root:

```bash
./mvnw -pl services/notification-service spring-boot:run
```

Notification defaults to the `local` profile: HTTP 8084, PostgreSQL 15432, Kafka 29092. All backend local datasource/Kafka ports now match Compose defaults; environment variables still override them. Explicitly selecting another profile disables the default local profile. Maven does not load Compose's `.env`. `KAFKA_BOOTSTRAP_SERVERS` overrides the complete broker address. The health endpoint is `/actuator/health`; existing deny-by-default HTTP security is unchanged. Stop the application with Ctrl+C.

| Setting | Default |
| --- | --- |
| `QUOTE_EVENTS_TOPIC` | `insurance.quote.events` |
| `NOTIFICATION_CONSUMER_GROUP` | `notification-service` |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` |
| `spring.kafka.consumer.enable-auto-commit` | `false` |
| `spring.kafka.listener.ack-mode` | `record` |
| Listener concurrency | 1 |

`earliest` is deliberate for learning historical consumption. It applies when a partition has no usable committed offset, including a brand-new group. Restarting a group with committed offsets resumes there; it does not replay everything. The Phase 12 historical test event can therefore create a notification referencing an already-deleted temporary quote/customer. Those UUIDs are logical references, not cross-service foreign keys. Notification does not query other services to check their existence.

The existing topic has **3 partitions**. One instance can consume all three. Instances sharing this group divide partitions; a partition has one active consumer in that group at a time. More than three consumers leave some idle. A future Audit consumer will use a different group and independently receive the same events. No Audit consumer is implemented here.

## Contract and validation

The consumer-owned `QuotePricedEnvelope` and `QuotePricedData` records match the [Phase 12 wire contract](../../docs/quote-priced-event.md). They do not create a new wire format or depend on Quote Service classes. Kafka uses String deserializers; `QuoteEventDecoder` explicitly maps JSON to the fixed DTO with Jackson, with no Java type headers, wildcard trusted packages, or polymorphic default typing.

Only QuotePriced version 1 is supported. Validation checks event/aggregate/customer/quote UUIDs, aggregate-to-payload ID equality, producer/aggregate names, UTC-compatible timestamps, a bounded safe correlation ID, quote reference, PRICED status, supported coverage, nonnegative premium with the existing precision/scale, THB currency, and expiration after pricing. Amounts are formatted to two decimals using BigDecimal; no pricing calculation or external HTTP call occurs. A mismatched/missing Kafka key is logged without echoing it; the validated payload remains authoritative.

| Input/result | Policy |
| --- | --- |
| New valid QuotePriced v1 | Persist marker and notification atomically, then return successfully |
| Duplicate eventId | Return DUPLICATE successfully without another business insert |
| Nonempty unsupported event type | Log intentional skip; return and advance offset |
| Malformed JSON or invalid supported-event data | Log safe reason and stop listener container; leave failed offset uncommitted |
| Unsupported QuotePriced version | Stop; never silently interpret it as v1 |
| Genuine database failure | Stop; leave failed offset uncommitted |

Malformed input is identified by topic/partition/offset. Validated event failures additionally log event ID, event type, quote ID and correlation ID. Payloads, exception messages and nested causes are not logged by the listener. The stopping handler emits its framework stack trace only at DEBUG. A stopped listener requires operator investigation and an explicit restart after remediation; repeated restarts encounter the same poison record. Do not casually skip/reset offsets. The existing HTTP health endpoint is not proof that consumption is progressing: inspect logs and group lag as well.

## Database and offset ordering

`QuoteEventListener` calls the separate Spring bean `NotificationService.create()`, annotated `@Transactional`. It inserts and flushes one row. The transaction proxy commits before returning to the listener. Only a successful listener return allows the container's RECORD mode to commit the next offset. No manual acknowledgment, Kafka transaction, distributed transaction, XA, retry topic, DLQ or application retry is configured. Spring's `CommonContainerStoppingErrorHandler` replaces the default retry/recovery handler; it stops rather than recovering/skipping failures. Normal client networking/rebalance behavior still applies.

Mapping:

| Column | Source |
| --- | --- |
| `id` | New UUID |
| `customer_id` | `data.customerId` |
| `quote_id` | `data.quoteId` |
| `event_id` | Envelope `eventId` |
| `type` / `status` | `QUOTE_PRICED` / `CREATED` |
| `message` | `Your quote <reference> is ready. Premium: <amount> <currency>.` |
| `created_at` / `processed_at` | Same injected Clock instant, UTC, PostgreSQL microsecond precision |

Only `notifications` and `processed_events` are written. Existing Flyway migrations and constraints remain unchanged; no new schema is required.

**Phase 15 makes duplicate delivery idempotent** using the existing processed_events table. See the transaction and historical compatibility details below. Retry/DLQ remains deferred to Phase 17 and Quote producer reliability remains deferred to Phase 16 Outbox.

## Tests and learning order

```bash
# No Docker needed:
./mvnw -pl services/notification-service test
./mvnw clean verify
# Docker required; separate profiles, disposable infrastructure:
./mvnw -pl services/notification-service -Ppostgres-it verify
./mvnw -pl services/notification-service -Pkafka-it verify
```

Read `event/QuotePricedEnvelope` and `QuotePricedData`, then `QuoteEventDecoder` → `QuoteEventListener` → `service/NotificationService` → `entity/Notification` and `repository/NotificationRepository` → `config/NotificationKafkaConfiguration` → `application.yml`.

Normal tests cover mapping, validation, safe errors, idempotency branches and existing HTTP startup. PostgreSQL and Kafka integration tests now verify atomic processed markers, duplicate success, historical compatibility, concurrency and true same-offset redelivery. The command profiles remain unchanged.

For live inspection:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group notification-service
```

The committed offset is the **next** record to read. For partitions containing processed records, successful catch-up has committed offset equal to log-end offset and lag zero. An empty partition may show no committed offset (`-`) and a log-end offset of zero. Consumer reads change group offsets, not topic end offsets. Keep Kafka history and offsets during ordinary cleanup.

See [Phase 13 specification](../../docs/specs/phase-13-notification-consumer.md) and [validation](../../docs/phase-13-validation.md). Framework references: [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) and [listener container acknowledgment modes](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html).

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

Deduplication identity is `(eventId, consumerName)`. The stable logical consumer name is **notification-service**, independent of instance IDs, hostnames and configurable Kafka group IDs. Changing the Kafka group does not change this logical identity. Notification and Audit use separate local databases and names, so each legitimately processes the same event once.

PostgreSQL's existing composite primary key chooses the winner. Concurrent duplicate attempts wait for the first transaction: after its commit the loser gets a no-op and skips business work; after its rollback another attempt may insert the marker and process normally. No SELECT-first race, in-memory lock, distributed lock or shared database is used. Business UNIQUE(event_id) constraints remain defense-in-depth.

For a normal new event, `processed_at` is the same injected UTC/microsecond processing instant used for the business row's creation time. Duplicate conflict handling does not update the marker or business row. For a pre-Phase-15 business row lacking a marker, the service first wins the atomic marker claim, then checks `existsByEventId`. It returns DUPLICATE and commits the new marker without rewriting the historical row. That marker's time is the first tracked replay time, not a reconstructed historical processing time. There is no startup scan or automatic bulk backfill, and no attempt to catch a unique violation inside an already-aborted PostgreSQL transaction.

The listener logs `result=PROCESSED` or `result=DUPLICATE` at INFO with safe event/type/consumer/quote/correlation/partition/offset metadata. Unsupported event types are still intentionally skipped without markers. Malformed supported events, unsupported versions and genuine database failures still stop the consumer; they are never converted into duplicate success. No retry/DLQ, Outbox, Kafka transaction or new event type is introduced.

Tests prove first/duplicate/different-event behavior, unchanged timestamps, marker and business rollback, stable names, lazy historical compatibility, concurrent commit and rollback outcomes. The Kafka test injects a test-only failure in the listener **after the service transaction commits but before the listener returns**, then restarts the same group. Both listener invocations receive partition 0 offset 0: true same-record redelivery, without offset resets or republishing. A separate second Kafka record carrying the same eventId also succeeds. Invalid JSON still stops without advancing past it. See [Phase 15 specification](../../docs/specs/phase-15-idempotent-consumers.md) and [validation](../../docs/phase-15-validation.md).
