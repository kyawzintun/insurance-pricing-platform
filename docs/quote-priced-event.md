# QuotePriced — Version 1

Status: Implemented in Phase 12. Producer: Quote Service. No application consumer yet.

## Routing and encoding

- Topic: `insurance.quote.events`, configurable with `QUOTE_EVENTS_TOPIC`.
- Key: canonical quote UUID string (`quoteId.toString()`), never customerId or eventId.
- Local topology: existing three partitions and replication factor one; no topic/config changes.
- Value: JSON from explicit records using Spring Kafka `JacksonJsonSerializer`; type-info headers disabled. BigDecimal premiums are JSON numbers and Instants are ISO-8601 UTC strings.

With an unchanged partition count and producer partitioner, the same quote key maps consistently to the same partition. This does not impose global order across different quote keys or make the database/Kafka write atomic.

## Envelope and data

```json
{
  "eventId": "c4087f65-dd60-477b-8bc1-7055a10e13ce",
  "eventType": "QuotePriced",
  "eventVersion": 1,
  "aggregateType": "Quote",
  "aggregateId": "367970f1-428e-41b5-8f3b-dfdc3f219634",
  "occurredAt": "2026-10-11T15:00:00Z",
  "correlationId": "example-quote-request",
  "producer": "quote-service",
  "data": {
    "quoteId": "367970f1-428e-41b5-8f3b-dfdc3f219634",
    "quoteReference": "Q-20261011-367970F1428E41B58F3BDFDC3F219634",
    "customerId": "d518c95d-7cbf-43aa-aa3f-a470e02b89ed",
    "status": "PRICED",
    "coverageType": "COMPREHENSIVE",
    "finalPremium": 11200.00,
    "currency": "THB",
    "pricedAt": "2026-10-11T15:00:00Z",
    "expiresAt": "2026-11-10T15:00:00Z"
  }
}
```

These UUIDs are illustrative. `eventId` is new for each constructed publication. `aggregateId` and `data.quoteId` identify the same persisted quote. `occurredAt` is captured using the injected Clock when the event is constructed, before commit; it is not the Kafka acknowledgement time. `pricedAt`/`expiresAt` and premium/currency come from the persisted response snapshot. `customerId` comes from authenticated quote ownership. No driver date of birth, contact details, JPA graph, password, JWT, Authorization header or signing secret is included.

## Correlation

Capture `X-Correlation-ID` on the request thread using the same `[A-Za-z0-9._-]{1,128}` convention as Gateway. Preserve a safe propagated ID; missing/invalid direct-service headers or calls without a request receive a UUID. This is diagnostic metadata, not trusted identity. It is captured in the envelope before the asynchronous Kafka callback.

## Commit and failure semantics

`QuotePersistenceService` saves and flushes the complete PRICED aggregate and registers an immutable Spring `QuotePricedEvent`. Flushing is not committing. `QuoteEventPublisher` handles it with `@TransactionalEventListener(AFTER_COMMIT)`, without fallback execution. Rollback, commit failure, failed Pricing and GET requests do not publish. No Kafka send occurs before the database commit.

After commit, one `KafkaTemplate.send` attempt is made. Synchronous metadata/buffer waiting is bounded by `max.block.ms=1000`; acknowledgement completion is asynchronous. No future is awaited by the HTTP handler. Synchronous enqueue/serialization errors and asynchronous delivery errors are caught/logged safely and do not undo the committed quote. The existing successful quote response remains HTTP 201 with PRICED status. It is possible for a failure log to appear after HTTP completion. There is no recovery/replay operation in this phase.

Failure logs contain event type/ID, quote ID, topic, correlation ID and failure class. They exclude payload and exception text/stack traces. The default template error listener is replaced because it can print record contents. Successful acknowledgements log partition/offset along with event metadata. Kafka client default retry/idempotence behavior is retained; no custom retries, DLQ publishing, transactions or consumers are configured.

**Phase 12 uses direct Kafka publishing after DB commit. There is a dual-write failure window:** a crash or producer failure can leave a quote in PostgreSQL without its event. Kafka acknowledgement/idempotent producer behavior does not close this gap. Phase 16 Transactional Outbox will address it. `outbox_events` remains untouched now.

## References and validation

The implementation follows Spring's [transaction-bound event semantics](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) and [JSON serialization configuration](https://docs.spring.io/spring-kafka/reference/kafka/serdes.html). See [Phase 12 specification](specs/phase-12-quote-priced-event.md) and [validation](phase-12-validation.md). Notification/Audit consumers, retry/DLQ processing and all other domain event types are deferred.

## Phase 13 consumer

Notification Service consumes this unchanged envelope using fixed consumer-owned DTOs. It stores the event customer/quote/event identifiers and formats the premium into a simulated notification. See [consumer behavior, offset semantics and limits](../services/notification-service/README.md).
