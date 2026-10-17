# Phase 18 — Quote Expiration

The 30-day lifetime at quote creation is unchanged. Only stored PRICED quotes with
`expires_at <= UTC Clock.instant()` are eligible, including equality. FAILED,
EXPIRED and all other statuses are ignored. No repricing or external service call
occurs. Ownership, history, original expiry, money, driver/vehicle snapshots and
pricing adjustments remain unchanged.

```text
PRICED Quote
    ↓ expires_at <= now
Expiration Scheduler
    ↓
DB transaction
    ├── status = EXPIRED, updated_at = processing time
    └── QuoteExpired outbox PENDING
    ↓
Outbox Publisher
    ↓
insurance.quote.events
    ├── Notification -> QUOTE_EXPIRED
    └── Audit -> SYSTEM QuoteExpired
```

## Scheduling and concurrency

`QUOTE_EXPIRATION_ENABLED=true`, `QUOTE_EXPIRATION_POLL_INTERVAL=60000` (milliseconds,
fixed delay and initial delay), `QUOTE_EXPIRATION_BATCH_SIZE=50` (allowed 1–500).
Tests disable background scheduling and call the transactional service explicitly.
Scheduling is independent of outbox publisher enablement.

Each poll processes one bounded batch, ordered by `expires_at, id`, using
`SELECT ... WHERE status='PRICED' AND expires_at <= ? ORDER BY expires_at, id
LIMIT ? FOR UPDATE SKIP LOCKED`. The existing `(status, expires_at)` index supports
eligibility. PostgreSQL locks remain held through commit; concurrent instances
skip locked rows and subsequent polls ignore committed EXPIRED rows. A conditional
status update must affect exactly one row before its outbox insert.

A batch uses one Spring transaction, including JDBC status updates and JPA outbox
inserts on the same datasource. Any write or commit failure rolls back the whole
bounded batch. A later poll retries; a persistently invalid row can prevent its
batch from committing and requires operator repair. This deliberately simple
strategy introduces no job framework. Logs describe staged work before commit;
empty polls do not produce INFO noise.

## QuoteExpired v1 contract

Common envelope: `eventType=QuoteExpired`, `eventVersion=1`, `aggregateType=Quote`,
`aggregateId=quoteId`, `producer=quote-service`. `eventId` is a new UUID.
`occurredAt` is UTC processing time at microsecond precision. A new UUID
correlation ID is generated for each expiration and used identically in the
envelope and outbox, independently of the original HTTP correlation.

Data: `quoteId`, `quoteReference`, `customerId`, `status=EXPIRED`, `coverageType`,
`finalPremium`, `currency`, `expiredAt` (processing time), `originalExpiresAt`
(stored expiry). No DOB, authentication material or additional PII is included.

The existing outbox persists PENDING, retry_count=0, published_at=null. Its generic
publisher sends the stored envelope to `insurance.quote.events`, keyed by quoteId,
then marks PUBLISHED. QuotePriced v1 is unchanged. Both types share the same key
and therefore partition ordering (offsets need not be consecutive). Existing
at-least-once publication and advisory-lock publisher behavior is unchanged.

## Consumers

Notification produces CREATED / QUOTE_EXPIRED with message
`Your quote <reference> has expired.` Audit records QuoteExpired with SYSTEM actor,
null actor_id (allowed by the existing schema), Quote entity and envelope
correlation/occurrence/producer. Metadata includes reference, status, coverage,
premium, currency and original/processing expiration timestamps.

Consumer-owned DTOs accept both fixed v1 payload shapes. QuoteExpired validates
its timestamps and EXPIRED status. Unknown event types still skip intentionally;
invalid supported events retain the existing permanent-error DLQ handling.
The Phase 15 eventId + consumerName transaction marker and Phase 17 retries/DLQ
remain unchanged. QuotePriced and QuoteExpired have separate event IDs, so both
produce business rows; duplicates of either event are successful no-ops.

No schema migration, topic, DLQ, frontend workflow, auth/user event or Phase 19
implementation is introduced.
