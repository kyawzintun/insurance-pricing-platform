# Pricing rule events v1 — Phase 20

Successful ADMIN mutations emit exactly one operation-specific event:

| Operation | Event |
| --- | --- |
| POST create | PricingRuleCreated |
| PUT replace editable fields | PricingRuleUpdated |
| PATCH enable | PricingRuleEnabled |
| PATCH disable | PricingRuleDisabled |

PUT emits Updated even when it changes enabled status. As in Phase 7, accepted
same-state PATCH requests still advance the rule version and emit the requested
Enabled/Disabled event. Stale/invalid/unauthorized requests emit nothing.

```text
ADMIN through Gateway
  ↓
Pricing rule mutation
  ↓
Pricing DB transaction
  ├── pricing_rules mutation + optimistic version flush
  └── complete pricing event JSON in outbox_events PENDING
  ↓
COMMIT
  ↓ HTTP success
Pricing Outbox Publisher
  ↓
insurance.pricing.events (key = pricingRuleId)
  ↓
Audit Service
  ↓ local transaction
audit_records + processed_events
```

HTTP success means the mutation and durable event intent committed, not that Kafka
or Audit finished. There is no direct Kafka send, AFTER_COMMIT publisher, DB/Kafka
dual write, CDC or distributed transaction. An outbox insert failure rolls back the
mutation; optimistic-lock failure occurs before inserting its intent. Only a winning
concurrent transaction commits an event. Existing @Version/409 behavior is retained.

## Contract

Each event uses the existing nine-field envelope, version 1, aggregateType=PricingRule,
aggregateId=pricingRuleId and producer=pricing-service. Example Created:

```json
{
  "eventId": "5549a1c8-611e-4988-a67a-8561d6e57a19",
  "eventType": "PricingRuleCreated",
  "eventVersion": 1,
  "aggregateType": "PricingRule",
  "aggregateId": "423d8a5a-ae20-4669-a347-f4ce324bfc6b",
  "occurredAt": "2026-10-19T00:00:00Z",
  "correlationId": "pricing-admin-example",
  "producer": "pricing-service",
  "data": {
    "pricingRuleId": "423d8a5a-ae20-4669-a347-f4ce324bfc6b",
    "ruleType": "DRIVER_AGE",
    "operator": "LESS_THAN",
    "comparisonValue": "21",
    "comparisonValueTo": null,
    "factor": 1.30,
    "fixedAmount": null,
    "effectiveFrom": "2030-01-01T00:00:00Z",
    "enabled": true,
    "version": 0,
    "changedBy": "00000000-0000-0000-0000-000000000001",
    "changedAt": "2026-10-19T00:00:00Z"
  }
}
```

The four types share this compact resulting-state snapshot. Fields come from the
actual model; no invented category/value or before/after graph. Description is
omitted. Base rules have fixedAmount and null factor/operands; adjustment rules
have factor and the existing comparison fields. Amounts are JSON numbers, not floats
computed by the producer. Version is the persisted version after flush (0 at creation).

changedBy comes only from an authenticated JwtAuthenticationToken with ROLE_ADMIN
and a UUID subject in Pricing's security context. Request fields cannot supply it.
Pricing still validates JWT independently of Gateway. Invalid actor identity produces
403 and rolls back the transaction. No JWT, Authorization, passwords, credentials,
signing keys or user names/emails are included. Listener/publisher logs use safe
identifiers and coordinates; PostgreSQL rejected-row details are disabled.

One injected UTC Clock instant (microseconds) supplies occurredAt/changedAt and
outbox created_at. Existing rule updatedAt still advances for same-state mutations,
including fixed-clock tests; it can differ slightly from changedAt by that existing
monotonic timestamp rule. Gateway X-Correlation-ID is preserved if it matches
`[A-Za-z0-9._-]{1,128}`; direct calls use a generated UUID fallback. Pricing V4 widens
only outbox correlation_id from 100 to 128. Old migrations and seed rows are untouched.

## Publisher and configuration

Pricing reuses Auth/Quote's service-local publisher design. A bounded PENDING batch
is ordered by created_at/id. Every Pricing instance uses PostgreSQL session advisory
lock `(741020, 1)`. A competing poll skips. One dedicated autocommit connection stays
borrowed for the batch; no database transaction remains open during Kafka I/O.
Use direct PostgreSQL/session pooling, not transaction-pooling proxies. This serializes
dispatch across instances rather than distributing batches.

The publisher sends exact stored JSONB `payload::text`, key=pricingRuleId, no type
headers, acks=all. Only acknowledgment permits PUBLISHED/published_at. Failed sends
leave PENDING, atomically increment retry_count and end that poll. Later polls resend
the same JSON, eventId, correlation, key and occurrence time. A successful send followed
by failed status update/crash can duplicate delivery: at-least-once, never exactly-once.
Acks and processing in Audit can occur before the PUBLISHED update completes.

| Environment variable | Default / behavior |
| --- | --- |
| PRICING_EVENTS_TOPIC | insurance.pricing.events, Pricing and Audit |
| PRICING_EVENTS_DLQ_TOPIC | insurance.pricing.events.dlq, Audit only |
| PRICING_OUTBOX_PUBLISHER_ENABLED | true locally, false outside local |
| PRICING_OUTBOX_POLL_INTERVAL | 2000 ms, initial/fixed delay |
| PRICING_OUTBOX_BATCH_SIZE | 50, valid 1–500 |
| KAFKA_BOOTSTRAP_SERVERS | local localhost:29092 |

Kafka waits use max.block.ms=1000, delivery.timeout.ms=5000, request.timeout.ms=1000.
Producer intent retries have no cutoff/DLQ; an oldest persistently failing intent can
delay later rows. Retry count counts failed application sends, not internal Kafka
retries. No retention/cleanup/backfill job is added. Topic/DLQ retain Phase 1 three
partitions and replication factor one. Applications do not create topics; provision
compatible source/DLQ partitions when overriding names.

## Audit

Audit adds focused PricingEventDecoder/PricingEventListener, independent of producer
classes and the existing quote/user decoders. Its stable audit-service logical
consumer identity and configurable Kafka group remain unchanged. Offsets are per
topic/partition. Notification has no pricing listener and receives no pricing changes.

Mapping: exact event_type; ADMIN actor/changedBy; PricingRule entity/pricingRuleId;
envelope correlation/occurredAt/producer; metadata is the fixed resulting-state DTO.
Existing schemas support this without migration. The processed_events marker and
audit row commit together. Deduplication remains `(eventId, consumerName)`; it is
not by rule ID, since successive legitimate rule changes each need an audit row.
Duplicates return success, preserving one business row and marker per event.

Phase 17 DefaultErrorHandler/FixedBackOff and the existing recovery callback are
reused. Invalid supported inputs/versions go directly to the configured pricing DLQ;
processing/DB failures retry with the existing bounded configurable attempts/backoff.
Unknown nonempty types intentionally skip. Exact key/value bytes, source partition
and allowlisted diagnostic headers survive. Source completion requires business
success or acknowledged DLQ recovery. Failed DLQ sends leave the source replayable.
Recovery is not atomic with source commit, so duplicate DLQ records remain possible.
No new recovery/header format or retry topics.

## Boundaries

Only successful admin writes after deployment produce these events. No startup scan
or historical pricing-rule backfill. Calculation remains synchronous/read-only and
emits no PricingCalculated event; Quote still owns QuotePriced. No Notification change,
frontend change, deletion/bulk events, admin customer management or Phase 21 work.

See [specification](specs/phase-20-pricing-events.md) and [validation](phase-20-validation.md).
