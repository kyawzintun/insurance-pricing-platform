# Kafka Design

Status: Implemented through Phase 17. Earlier phase sections record historical milestones.

Kafka carries QuotePriced events to Notification and Audit. Quote Service uses Transactional Outbox publishing; both consumers use idempotent processing. See the Phase 16 section below for current behavior.

Phase 0 left topics, partitioning, event schemas, delivery handling, and consumer behavior unspecified. Phase 1 defines only the broker and topics below; event contracts and application behavior remain pending.

## Phase 1 — Local Broker and Topics

A single `apache/kafka:4.1.2` broker runs in KRaft combined mode. Separate listeners advertise `localhost:9092` to host clients and `kafka:19092` to Compose-network clients (the host port is configurable). Data and KRaft metadata reside on a named volume. This follows the [official Kafka Docker image configuration](https://hub.docker.com/r/apache/kafka/).

The following domain-oriented topics are initialized automatically:

- `insurance.user.events`
- `insurance.quote.events`
- `insurance.pricing.events`
- `insurance.user.events.dlq`
- `insurance.quote.events.dlq`
- `insurance.pricing.events.dlq`

All six topics use exactly 3 partitions and replication factor 1 locally. Broker-side automatic topic creation is disabled. The initialization script uses `--if-not-exists`, so existing topics are preserved; it does not alter existing partition counts or replication factors. Kafka's metadata API supplies the readiness check before initialization starts.

Event schemas, application producers/consumers, retry/DLQ behavior, consumer idempotency, and Transactional Outbox remain deferred. Creating DLQ topics does not implement error handling.

## Phase 2 — Connection Configuration Only

Quote, Pricing, Notification, and Audit include Spring Kafka through the Boot Kafka starter. Their local bootstrap addresses support `KAFKA_BOOTSTRAP_SERVERS`, falling back to `localhost:${KAFKA_EXTERNAL_PORT:9092}`. No application producer, listener, consumer, or topic declaration exists. Live validation found no changes to domain topic offsets or topic configuration. Auth and Gateway have no Kafka dependency.

## Phase 3 — Messaging Storage Only

Auth, Quote, and Pricing now own `outbox_events` tables. Notification and Audit own `processed_events` tables. These are schema foundations only: no publishers, consumers, processing, retries, or DLQ behavior exist. Live validation preserved Kafka topics, partition offsets, and consumer groups.

## Phase 12 — First application producer

Quote Service now publishes version 1 `QuotePriced` on the existing `insurance.quote.events` topic, keyed by quote UUID. The common envelope and compact payload are defined in [QuotePriced event contract](quote-priced-event.md). JSON type headers are disabled. Injected UTC Clock and bounded propagated/generated correlation IDs supply metadata.

A Spring transactional listener sends after PostgreSQL commit. The send acknowledgement is asynchronous; producer failure is logged safely without changing successful quote persistence/HTTP 201. This direct dual write can lose an event after a successful database commit. Phase 16 Transactional Outbox is deferred; existing outbox tables are not written. No application consumer, consumer group, DLQ processing, custom retry policy or other domain event is introduced. Existing topic configuration is preserved.

## Phase 13 — Notification Consumer

Notification Service consumes QuotePriced v1 from `insurance.quote.events` in its dedicated configurable `notification-service` group. It writes only simulated CREATED notifications in its own database, with no external HTTP/provider calls. Consumer-owned DTOs match the unchanged Phase 12 wire contract. Earliest offset reset deliberately supports historical learning records; committed offsets take precedence on restart. RECORD acknowledgment follows the local database transaction commit. Invalid supported events, unsupported versions and persistence failures stop consumption without skipping the failed offset; unknown event types are intentionally logged/skipped. Existing UNIQUE(event_id) is preserved but is not full idempotency. No processed_events, retry/DLQ, Audit consumer or outbox logic is implemented. See [Notification guide](../services/notification-service/README.md), [specification](specs/phase-13-notification-consumer.md), and [validation](phase-13-validation.md).

## Phase 14 — Independent Audit consumer

Audit now consumes the unchanged QuotePriced v1 envelope using its own configurable `audit-service` group, separate from `notification-service`. Both groups independently receive the same records. Audit writes only audit_records, with CUSTOMER/customerId attribution, Quote/quoteId entity identity, preserved correlation/occurrence data, and compact allowlisted JSONB metadata. RECORD offset commits follow local DB commit; unsupported types are skipped and invalid supported events/database failures stop the container. Earliest supports historical learning events. UNIQUE(event_id) remains visible without processed_events/idempotency, retry/DLQ or Outbox logic. A necessary Audit-only V3 widens correlation_id to 128 to match the existing producer contract, preserving V1/V2 checksums. See [Audit guide](../services/audit-service/README.md), [specification](specs/phase-14-audit-consumer.md) and [validation](phase-14-validation.md).

## Phase 15 — Idempotent Notification and Audit consumers

Both consumers now use their own existing processed_events tables, keyed by eventId and stable logical consumer name (notification-service / audit-service). An atomic ON CONFLICT insert claims the event inside the same local JPA transaction as its business write. Conflict returns DUPLICATE successfully, without rewriting the business row or original processed_at; RECORD acknowledgment may then advance the offset. Concurrent attempts are serialized by PostgreSQL uniqueness. A replay of a pre-Phase-15 business row lazily establishes its missing marker only after the atomic claim, without bulk backfill or rewriting the row. Genuine failures preserve stop/uncommitted-offset behavior. No schema, producer contract, group/topic, retry/DLQ or Outbox changes. See [specification](specs/phase-15-idempotent-consumers.md) and [validation](phase-15-validation.md).

## Phase 16 — Quote Transactional Outbox

Quote creation now stores the aggregate and a complete QuotePriced v1 JSONB envelope in the same transaction. The direct after-commit publisher is removed. HTTP 201 means durable quote/event intent; scheduled polling publishes later with the same event ID, correlation, occurrence time, topic and quote UUID key. An autocommit connection with a shared PostgreSQL session advisory lock excludes competing dispatchers without holding a transaction during Kafka I/O. Acknowledged sends become PUBLISHED; failed attempts remain PENDING and increment retry_count. Duplicate publication remains possible and Phase 15 consumers handle it unchanged.

Quote V6 widens only outbox correlation_id to 128 to match the existing contract; no other schema or seed changes, historical backfill, Auth/Pricing outbox, or Phase 17 retry/DLQ policy. The preceding phase sections describe historical milestones; this section supersedes their producer limitations. See [design and operational limits](transactional-outbox.md), [specification](specs/phase-16-transactional-outbox.md), and [validation](phase-16-validation.md).

## Phase 17 — Consumer retry and DLQ

Notification and Audit now use Spring Kafka DefaultErrorHandler with configurable fixed backoff (default three total attempts, 1000 ms). Invalid supported events go directly to the existing insurance.quote.events.dlq; unknown event types still skip and idempotent duplicates still succeed. Raw key/value bytes are preserved, with safe failure headers identifying the source coordinates and failing service/group. Source offsets advance only after business success or acknowledged DLQ publication; failed DLQ sends leave the source replayable. Independent group failure/recovery was verified with two actual packaged services in disposable infrastructure.

The QuotePriced contract, Quote outbox, processed-event semantics, schemas and topic topology are unchanged. Earlier phase stopping-handler descriptions are historical and superseded here. No replay consumer, retry topics, new business events or Phase 18 work. See [policy and limitations](retry-and-dlq.md), [specification](specs/phase-17-retry-and-dlq.md), and [validation](phase-17-validation.md).

## Phase 18 — Quote Expiration

Automatic PRICED → EXPIRED transitions use bounded PostgreSQL transactions and the existing outbox. Notification supports QUOTE_EXPIRED; Audit uses a SYSTEM actor. See the [Phase 18 specification](specs/phase-18-quote-expiration.md) for configuration, contract, concurrency and failure semantics. Phase 19 is outside this change.
