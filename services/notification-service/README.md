# Notification Service — Phase 13

The first application Kafka consumer. A valid QuotePriced v1 event creates a simulated notification in `notification_db.notifications`; there is no email/SMS integration or notification REST API.

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
| Valid QuotePriced v1 | Persist notification, then return successfully |
| Nonempty unsupported event type | Log intentional skip; return and advance offset |
| Malformed JSON or invalid supported-event data | Log safe reason and stop listener container; leave failed offset uncommitted |
| Unsupported QuotePriced version | Stop; never silently interpret it as v1 |
| Database failure, including duplicate event ID | Stop; leave failed offset uncommitted |

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

Only `notifications` is written. Existing Flyway migrations and `processed_events` are untouched. No new schema is required.

**This is not idempotent processing.** If the DB commits but the Kafka offset does not, redelivery attempts another insert. Existing UNIQUE(event_id) rejects it and stops consumption. It prevents a duplicate row but does not recognize the event as successfully processed or advance its offset. Phase 15 will add processed_events idempotency; Phase 17 will add retry/DLQ handling. Quote's producer reliability gap remains until Phase 16 Outbox.

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

Normal tests cover mapping, validation, unsupported type/version, safe errors, key mismatch, and existing HTTP startup. PostgreSQL tests cover exact persisted fields, unique event IDs, rollback and an empty processed_events table. Kafka integration tests consume historical JSON, verify DB persistence plus group offsets, demonstrate intentional unknown-type skipping, duplicate-key failure without offset advancement, replay after test-only conflict removal, and malformed-JSON stopping. Test-only maintenance affects disposable containers; it is not an application deduplication feature.

For live inspection:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group notification-service
```

The committed offset is the **next** record to read. For partitions containing processed records, successful catch-up has committed offset equal to log-end offset and lag zero. An empty partition may show no committed offset (`-`) and a log-end offset of zero. Consumer reads change group offsets, not topic end offsets. Keep Kafka history and offsets during ordinary cleanup.

See [Phase 13 specification](../../docs/specs/phase-13-notification-consumer.md) and [validation](../../docs/phase-13-validation.md). Framework references: [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) and [listener container acknowledgment modes](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html).
