# Phase 12 — Basic QuotePriced Kafka Event

Status: Complete; see [validation](../phase-12-validation.md). Source: approved Phase 12 request.

## Scope and contract

Quote Service publishes the version 1 `QuotePriced` envelope to configurable `QUOTE_EVENTS_TOPIC` (default `insurance.quote.events`), using the canonical quote UUID string as key. Envelope fields: eventId, eventType, eventVersion, aggregateType, aggregateId, occurredAt, correlationId, producer, data. Payload contains quoteId/reference, customerId, PRICED status, coverageType, finalPremium/currency, pricedAt/expiresAt. No personal driver/vehicle aggregate, credentials, JWT or signing key.

## Timing and failure policy

Persist the complete aggregate and publish a Spring application event inside the transaction. A transactional listener sends only AFTER_COMMIT with fallback execution disabled. DTO construction uses injected Clock and captures the bounded Gateway correlation ID on the request thread; direct/programmatic calls receive a generated UUID when absent/unsafe.

Kafka send is asynchronous after commit. Synchronous metadata/buffer waiting is bounded by max.block.ms=1000. Synchronous enqueue and asynchronous broker failures are logged using metadata and failure class only, not payload or exception text. They do not change the successful 201/persisted PRICED quote. Kafka's default producer reliability/retries are retained; no application retries, DLQ or consumers. Replace KafkaTemplate's default payload-logging listener with the publisher's safe logging.

This is a deliberate database/Kafka dual-write gap: process loss or publishing failure can leave a committed quote without an event. There is no replay/durable queue or exactly-once claim. Phase 16 Transactional Outbox will address this. Do not write outbox rows now.

## Validation

Normal tests must remain Docker-free. Test envelope/serialization/correlation, topic/key, safe failures, and no events from failed pricing or GETs. Strong PostgreSQL transaction tests must prove no send before commit, one send on commit, no send on rollback and persistence survives Kafka failure. Opt-in disposable Kafka testing should validate wire JSON, same-key partition stability and one event per successful create. Live Gateway creation must advance exactly one quote-topic partition by one; other offsets, catalog, rules, schemas and outbox remain unchanged. Clean temporary SQL data, retain the identifiable append-only Kafka event, and stop task-started processes.
