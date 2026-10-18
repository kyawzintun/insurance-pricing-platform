# Consumer Retry and Dead Letter Queue — Phase 17

Notification and Audit use the same policy for QuotePriced consumption. Quote's Phase 16 transactional outbox, the v1 JSON contract, business processing, and Phase 15 idempotency are unchanged.

## Flow and acknowledgment

```text
insurance.quote.events
        ↓
Notification / Audit (independent groups)
        ↓
validate and process
   ┌────┴─────────────────┐
 success                failure
   │                      ↓
   │           retry if retryable
   │                      ↓
   │              exhausted / invalid
   │                      ↓
   │       insurance.quote.events.dlq
   │                      ↓
   │              Kafka acknowledges
   └────────── source offset advances
```

A source record is complete only after the local business transaction succeeds (including DUPLICATE) or its DLQ publication is acknowledged. If DLQ publication fails or times out, the recovery callback throws. The error handler seeks/redelivers the source record instead of successfully completing it. Later work on that source partition cannot pass it until processing or DLQ recovery succeeds. With one consumer thread, retries can also delay other partitions assigned to that thread; another consumer group remains independent.

RECORD acknowledgment and disabled auto-commit are preserved. No manual acknowledgment, Kafka transaction, XA/2PC, retry topic, or DLQ consumer is introduced. Standard `DefaultErrorHandler` has `ackAfterHandle=true`; `commitRecovered` for MANUAL_IMMEDIATE is not used because these consumers remain in RECORD mode.

## Policy and settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `KAFKA_CONSUMER_MAX_ATTEMPTS` | 3 | Total processing attempts, including the first; range 1–10 |
| `KAFKA_CONSUMER_BACKOFF_MS` | 1000 | Fixed wait between retryable failures; range 0–10000 milliseconds |
| `NOTIFICATION_CONSUMER_GROUP` | notification-service | Notification's Kafka group |
| `AUDIT_CONSUMER_GROUP` | audit-service | Audit's Kafka group |
| `QUOTE_EVENTS_TOPIC` | insurance.quote.events | Existing source topic |
| DLQ destination | insurance.quote.events.dlq | Existing fixed shared DLQ topic |

Three total attempts mean initial processing plus two retries: `FixedBackOff(backoffMs, maxAttempts - 1)`. Invalid configuration fails startup. Changing exception classes during processing does not restart the attempt budget. Attempt state is in memory; it is not a durable global delivery counter. Restart/rebalance can restart processing attempts.

| Outcome | Policy |
| --- | --- |
| New valid QuotePriced v1 | Atomically write marker/business row, then acknowledge source progress |
| Already processed event ID | DUPLICATE success, no retry or DLQ |
| Valid nonempty unsupported event type | Intentional skip/commit, no marker or DLQ |
| Malformed UTF-8/JSON, null input, invalid envelope/data, unsupported version, missing mandatory fields, aggregate mismatch | Non-retryable; one processing attempt then DLQ |
| Database/transaction failure or unexpected runtime processing failure | Retry up to configured total, then DLQ |
| DLQ publication failure | Source remains replayable; later recovery attempts continue until possible |

After a failed DLQ recovery, the framework resets its recovery state. A retryable record can therefore run another bounded processing cycle before another DLQ attempt; a permanent invalid record goes directly back to recovery. There is deliberately no promise of a global maximum processing count while the DLQ is unavailable. No failed record is silently dropped to enforce a count.

## Implementation and why it is small

The installed Spring Kafka version is 4.1.1. `NotificationKafkaConfiguration` and `AuditKafkaConfiguration` create standard `DefaultErrorHandler` instances using `FixedBackOff`. The only non-retryable application exception is `InvalidQuoteEventException`; classification traverses framework wrapper exceptions. All other processing failures are retryable.

Listeners still delegate business work to the same transactional services. A `QuoteProcessingException` distinguishes retryable business failures from invalid input and carries only the underlying class name, with a fixed message and no payload-bearing cause.

`QuoteDeadLetterRecoverer` implements Spring Kafka's `ConsumerRecordRecoverer` and `RetryListener` callbacks. The framework owns retry tracking, backoff and redelivery; there is no retry loop inside either listener. The focused callback constructs allowlisted headers and sends the original bytes, returning only after acknowledgment. This is the supported custom recoverer alternative to `DeadLetterPublishingRecoverer`, used here to avoid forwarding arbitrary incoming headers or framework-generated stack traces and to keep acknowledgment behavior explicit.

Kafka producer configuration uses byte-array serializers, `acks=all`, max.block.ms=1000, request.timeout.ms=1000 and delivery.timeout.ms=5000. Recovery waits up to 7 seconds on the send future. A send timeout is an unknown delivery outcome; it throws rather than claiming completion. Kafka's own networking retries remain separate from application processing attempts. The default producer error listener is replaced with metadata-only application logging.

## Original message and failure metadata

Both key and value are consumed as byte arrays and published unchanged. The decoder strictly converts UTF-8 and then applies the existing JSON/semantic validation. Invalid UTF-8, malformed JSON, binary keys and null values do not need successful event deserialization to reach DLQ. Topic/type/version/JSON business contract are unchanged; there are no Java type headers or polymorphic deserialization.

DLQ records preserve the source partition. Phase 1 defines three partitions for both source and DLQ. Topic configuration is not changed. If a source-topic override is used, it must have compatible partitioning; an unavailable/invalid destination fails recovery and does not acknowledge the source.

Headers are constructed from a fresh allowlist. Incoming headers are not forwarded, preventing spoofed failure metadata, credentials, or old exception stack traces from being copied.

| Header | Value |
| --- | --- |
| `x-original-topic` | Source topic |
| `x-original-partition` | Source partition, decimal UTF-8 text |
| `x-original-offset` | Source record offset, decimal UTF-8 text |
| `x-original-key` | Exact original key bytes; absent for null key |
| `x-consumer-group` | Actual configured group |
| `x-consumer-service` | Stable notification-service or audit-service |
| `x-exception-class` | Safe underlying processing exception class, or invalid-input exception class |
| `x-error-message` | Fixed reason code, never the original exception message |
| `x-failed-at` | UTC instant at the DLQ publication attempt |
| `x-attempt-count` | Processing-attempt count for this recovery cycle |
| `x-event-id` | Original event ID if readable and UUID-shaped; otherwise absent |
| `x-correlation-id` | Original correlation if it meets the established 1–128-character safe format; otherwise absent |

The framework failure callback replaces a local attempt header for bookkeeping; that header is never copied to DLQ. Permanent invalid input always records attempt 1. There are no stack-trace headers. Logs use source coordinates, service, attempt, safe identifiers and exception class; they omit keys, full values and raw exception messages. PostgreSQL rejected-row details are disabled for these application connections to avoid logging business data on insert failures.

A shared DLQ can legitimately hold two records for one source coordinate: one from Notification and one from Audit. Neither eventId nor source offset alone uniquely identifies a consumer failure; include service/group.

## Relationship to idempotency and outbox

Retries always re-enter the existing service transaction: atomic processed-event marker claim followed by business persistence. A failed transaction rolls back both. A committed marker means a later delivery succeeds as DUPLICATE without another business write. A crash between database commit and source offset commit remains safe.

Consumer processing attempts are unrelated to `quote_db.outbox_events.retry_count`. That column counts failed Quote producer publication attempts. Consumer retry counts exist only in error-handler state/logs and DLQ metadata; no new database column or migration was added.

DLQ publication and source offset commit are not atomic. If the DLQ send succeeds but the process crashes before source commit, or acknowledgment is lost, DLQ publication may happen again. This is at-least-once behavior, not exactly-once. No automatic replay or DLQ deduplication is implemented.

## Running and inspecting

Normal startup remains:

```bash
./mvnw -pl services/notification-service spring-boot:run
./mvnw -pl services/audit-service spring-boot:run
```

Run each in its own terminal. Local PostgreSQL/Kafka defaults remain 15432/29092. An optional per-run override is:

```bash
KAFKA_CONSUMER_MAX_ATTEMPTS=3 KAFKA_CONSUMER_BACKOFF_MS=1000 \
  ./mvnw -pl services/notification-service spring-boot:run
```

Inspect durable group progress:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group notification-service

docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group audit-service
```

For bounded DLQ inspection, choose a known partition and record offset, without a consumer group or commits:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:19092 --topic insurance.quote.events.dlq \
  --partition 0 --offset 0 --max-messages 1 --timeout-ms 10000 \
  --consumer-property enable.auto.commit=false \
  --property print.key=true --property print.headers=true
```

Raw malformed/binary data may not render as readable terminal text. Do not publish poison records into shared topics or reset durable groups merely to experiment; use the disposable integration profiles. DLQ records can contain sensitive original input, so their access and retention require operational care.

No replay API, DLQ consumer, replay automation, retry topics, exponential policy, QuoteExpired, outbox cleanup or new metrics framework was added. Dedicated retry/DLQ counters are deferred; existing Actuator remains available. See [specification](specs/phase-17-retry-and-dlq.md) and [validation](phase-17-validation.md).

Framework reference: [Spring Kafka error handling and recovery](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).

## Phase 19 — Auth/User Events

Auth now persists UserRegistered v1 in its existing outbox during registration, then
publishes to insurance.user.events keyed by userId. Notification and Audit reuse
their idempotency and retry/DLQ paths with insurance.user.events.dlq. Auth V4 widens
outbox correlation_id to 128; consumer schemas need no migration. No historical
backfill or admin status events. See [contract and lifecycle](user-registered-event.md)
and [validation](phase-19-validation.md). Earlier phase sections are historical.
