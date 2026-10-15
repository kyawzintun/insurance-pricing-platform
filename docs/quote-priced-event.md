# QuotePriced — Version 1

Status: Contract introduced in Phase 12; durable outbox publishing implemented in Phase 16. Producer: Quote Service. Consumers: Notification and Audit.

## Routing and encoding

- Topic: `insurance.quote.events`, configurable with `QUOTE_EVENTS_TOPIC`.
- Key: canonical quote UUID string (`quoteId.toString()`), never customerId or eventId.
- Local topology: existing three partitions and replication factor one; no topic/config changes.
- Value: Complete envelope serialized from explicit records into JSONB; the stored JSON text is sent using Kafka `StringSerializer`, with no type-info headers. BigDecimal premiums are JSON numbers and Instants are ISO-8601 UTC strings.

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

These UUIDs are illustrative. `eventId` is generated once during quote creation and reused on every publication attempt. `aggregateId` and `data.quoteId` identify the same persisted quote. `occurredAt` is captured using the injected Clock when the event is constructed, before commit; it is not the Kafka acknowledgement time. `pricedAt`/`expiresAt` and premium/currency come from the persisted response snapshot. `customerId` comes from authenticated quote ownership. No driver date of birth, contact details, JPA graph, password, JWT, Authorization header or signing secret is included.

## Correlation

Capture `X-Correlation-ID` on the request thread using the same `[A-Za-z0-9._-]{1,128}` convention as Gateway. Preserve a safe propagated ID; missing/invalid direct-service headers or calls without a request receive a UUID. This is diagnostic metadata, not trusted identity. It is captured in the envelope during quote creation, before outbox persistence.

## Commit and failure semantics

`QuotePersistenceService` saves and flushes the complete PRICED aggregate plus exactly one PENDING outbox row within one transaction. Rollback or commit failure removes both. Pricing failures and GET requests create neither event intent nor publication.

HTTP 201 means the quote and event intent have committed. Kafka is contacted later by a scheduled publisher, which sends the exact stored JSON with the quote UUID key and marks PUBLISHED only after acknowledgment. Failed sends remain PENDING. The same event ID, occurredAt and correlation ID survive recovery and repeat publication.

The Phase 12 after-commit listener is removed. This closes the event-loss gap for newly committed quotes, while allowing at-least-once duplicates if an acknowledgment is lost or the PUBLISHED update fails. Phase 15 consumers prevent duplicate business effects. No historical quote backfill occurs.

See [transactional outbox design](transactional-outbox.md), [Phase 16 specification](specs/phase-16-transactional-outbox.md), and [validation](phase-16-validation.md). The original [Phase 12 validation](phase-12-validation.md) remains a historical record.

## Phase 13 consumer

Notification Service consumes this unchanged envelope using fixed consumer-owned DTOs. It stores the event customer/quote/event identifiers and formats the premium into a simulated notification. See [consumer behavior, offset semantics and limits](../services/notification-service/README.md).

## Phase 14 consumer

Audit independently consumes this same envelope in its audit-service group. The wire contract is unchanged; an Audit-owned migration widens its correlation column to preserve the contract's existing 128-character limit. See [Audit mapping and group semantics](../services/audit-service/README.md).

## Phase 15 duplicate handling

The contract, topic, key and version are unchanged. Notification and Audit deduplicate locally by the existing envelope eventId plus their stable logical consumer name. Both can process the same event independently; a repeated delivery within either consumer succeeds without another business row. See [idempotency specification](specs/phase-15-idempotent-consumers.md).
