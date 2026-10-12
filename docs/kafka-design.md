# Kafka Design

Status: Approved high-level intent recorded; detailed design pending migration from approved planning notes.

Kafka is planned for asynchronous domain events, including notifications and audit logging. Transactional Outbox will be introduced later.

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
