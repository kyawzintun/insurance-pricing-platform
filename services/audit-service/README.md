# Audit Service — Phase 14

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

`QuotePricedEnvelope` and `QuotePricedData` are consumer-owned DTOs matching the [unchanged Phase 12 contract](../../docs/quote-priced-event.md). They are not a new event format. Kafka uses String deserializers; `QuoteEventDecoder` explicitly maps to the DTO with Jackson. No Java type headers, wildcard trusted packages, polymorphic typing, JPA deserialization, or generic routing framework is used.

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
| Valid QuotePriced v1 | Commit one audit row, then advance Kafka progress |
| Unsupported nonempty event type | Intentionally log/skip and advance its offset |
| Unsupported QuotePriced version, malformed JSON or invalid data | Log safe reason; stop listener, leave failed offset uncommitted |
| Database failure, including duplicate event ID | Log metadata; stop listener without treating it as success |

Spring's `CommonContainerStoppingErrorHandler` replaces default retry/recovery behavior. No application retry/backoff, DLQ producer/consumer, retry topic or recoverer is configured. Normal Kafka client networking and rebalances still apply. A poison record requires investigation and an explicit restart after remediation; simply restarting repeats the failure. Never casually reset offsets to hide it. A stopped Audit consumer does not stop Notification's independent group. The HTTP health endpoint alone does not prove Kafka progress: check logs and group lag.

The listener logs fixed reason codes, topic/partition/offset and validated event/quote/correlation metadata. It does not log payloads or nested exception messages; the stopping handler's framework trace is DEBUG-only.

This phase is **not idempotent**. If the row commits and the consumer crashes before its offset commits, redelivery tries another insert. Existing UNIQUE(event_id) rejects the duplicate and stops processing. Preventing duplicate rows is not the same as treating an already-processed event as successful. `processed_events` stays unused; Phase 15 will address this for both consumers. No Outbox or external provider is added.

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

PostgreSQL tests verify exact mapping, JSONB, timestamps, 128-character correlation compatibility, uniqueness and rollback. Kafka tests verify historical consumption, successful offset advancement, unsupported-type skipping, duplicate failure without offset advancement, explicit restart after disposable-test-only conflict removal, and malformed input stopping. No test adds application deduplication logic.

Inspect the two independent groups:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group notification-service

docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group audit-service
```

Committed offsets identify the next record to read. Each group should independently catch up to topic end offsets; empty partitions may display no committed offset (`-`). Consumer reads do not change topic end offsets. Kafka records and group offsets remain durable during ordinary SQL cleanup.

See [Phase 14 specification](../../docs/specs/phase-14-audit-consumer.md) and [validation evidence](../../docs/phase-14-validation.md). Phase 15 is not implemented.
