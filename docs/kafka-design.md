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
