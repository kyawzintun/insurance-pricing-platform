# Phase 17 — Retry and DLQ

Status: Implementation complete; validation evidence is recorded in [Phase 17 validation](../phase-17-validation.md).

## Goal and scope

Add bounded retry and acknowledged dead-letter recovery to Notification and Audit only. Preserve QuotePriced v1, Phase 16 outbox, Phase 15 idempotency, service-owned schemas, existing topics, APIs and Angular behavior. No Phase 18 work.

## Functional behavior

Three total attempts by default, fixed 1000 ms backoff; both configurable with validated bounds. Retry processing/transaction failures. Invalid supported messages go directly to DLQ; valid unknown event types retain intentional skip behavior. Duplicates remain successful through existing processed_events logic.

## API and database impact

No REST/UI changes or new endpoints. No migrations, table changes, pricing rules, vehicle seeds, or outbox changes. Failed processing transactions roll back marker and business writes. Successful retry creates one business row and one marker. Existing late duplicate and historical-row compatibility remain.

## Kafka impact

Use standard DefaultErrorHandler/FixedBackOff with a focused ConsumerRecordRecoverer callback; no handwritten listener retry loop. Preserve exact key/value bytes and source partition on existing insurance.quote.events.dlq. Include authoritative service/group/source coordinates and safe failure headers. Source progress advances only after business success or acknowledged DLQ publication. A failed DLQ send leaves the source record replayable. Notification and Audit fail independently.

## Security considerations

Strict UTF-8 + fixed DTO validation; no polymorphic deserialization. Fresh allowlisted failure headers, no original credential headers, no stack traces or raw exception text. Original payload is deliberately retained for investigation and must be treated as potentially sensitive. Safe event/correlation identifiers are included only when readable and validated.

## Testing requirements

- Unit mapping/classification, exact bytes and safe headers, acknowledgment waiting and failures, configuration validation.
- Existing PostgreSQL idempotency/rollback/concurrency regressions, without Kafka in postgres-it.
- Real Kafka/PostgreSQL: transient failed transaction then success, three-attempt exhaustion, invalid input direct DLQ, unsupported type skip, duplicate success, same-offset crash recovery, destination outage preserving source progress, and later recovery.
- Two actual consumers against one broker: failure of one group's DLQ producer does not block the other; restoring it recovers the same record with distinct service/group headers.
- Shared live validation only when safe and authorized; never reset historical offsets or damage shared schemas.

## Definition of Done

Full Maven and both consumer Kafka/PostgreSQL profiles pass; failure/header/offset evidence recorded; independent group behavior demonstrated; historical data and existing producer semantics preserved. See [policy and limits](../retry-and-dlq.md).

Deferred: automatic/manual replay tooling, DLQ consumers, retry topics, exponential policy, durable attempt counters, exactly-once semantics, retention jobs, observability expansion, new business events, and Phase 18 Quote Expiration.
