# UserRegistered v1

New registrations after Phase 19 commit a user and immutable event intent in the
same Auth PostgreSQL transaction. Existing users are unchanged; no historical
backfill runs. HTTP 201 means both writes committed, independently of Kafka availability.

```text
Gateway registration
        ↓
Auth transaction
    ├── user
    └── UserRegistered outbox PENDING
        ↓
      COMMIT
        ↓
      HTTP success
        ↓
Auth Outbox Publisher
        ↓
insurance.user.events (key = userId)
    ├── Notification → USER_REGISTERED / CREATED
    └── Audit → UserRegistered / CUSTOMER
```

The common envelope has exactly nine fields:

```json
{
  "eventId": "6105422e-0262-44b1-8177-82bfc00ec58b",
  "eventType": "UserRegistered",
  "eventVersion": 1,
  "aggregateType": "User",
  "aggregateId": "22410825-5c00-4df3-9598-f79833ee28eb",
  "occurredAt": "2026-10-17T00:00:00Z",
  "correlationId": "registration-example",
  "producer": "auth-service",
  "data": {
    "userId": "22410825-5c00-4df3-9598-f79833ee28eb",
    "email": "customer@example.com",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "registeredAt": "2026-10-17T00:00:00Z"
  }
}
```

Email is the registered canonical trimmed/lowercase address. It is event data for
future welcome delivery, never a key, diagnostic header or logged payload. No
password, password hash, token, Authorization header, signing key, names or login
metadata is included. Audit metadata stores only role/status, omitting email.
Notification stores a simulated welcome message, user UUID, null quote_id and eventId;
no email/SMS is sent. Audit uses CUSTOMER actor and user UUID for actor/entity IDs,
entity_type=User, envelope producer/correlation/occurrence time.

Auth captures one injected UTC Clock instant at microsecond precision for user
creation, occurredAt, registeredAt and outbox created_at. It generates eventId once.
A propagated X-Correlation-ID matching `[A-Za-z0-9._-]{1,128}` is preserved; otherwise
Auth generates a UUID. Auth V4 widens only outbox correlation_id from 100 to 128.
Older Flyway migrations remain unchanged. Consumer schemas already support this event.

The service-local outbox publisher reuses Quote's proven design: bounded PENDING
batch ordered by created_at/id, PostgreSQL session advisory lock `(741019, 1)`
shared by Auth instances, autocommit statements, no database transaction across
Kafka I/O. One dedicated pool connection stays occupied per batch. Session pooling
or direct PostgreSQL is required. Lock ownership serializes publishers; process or
connection failure can still produce duplicates.

Stored JSONB `payload::text` is sent verbatim with userId key, acks=all, no type
headers. Only acknowledgment permits PUBLISHED/published_at. Send failure leaves
PENDING and increments retry_count; processing stops for this poll. A later poll
resends identical bytes, eventId, correlation and timestamps. A crash after broker
acceptance but before status update can resend the same event: at-least-once,
never exactly-once. Producer retries have no cutoff/DLQ, matching Quote's outbox.
A persistently failing oldest event can delay later work. Kafka network waits use
max.block.ms=1000, delivery.timeout.ms=5000 and request.timeout.ms=1000.

| Variable | Default |
| --- | --- |
| AUTH_OUTBOX_PUBLISHER_ENABLED | true locally; false otherwise |
| AUTH_OUTBOX_POLL_INTERVAL | 2000 ms fixed/initial delay |
| AUTH_OUTBOX_BATCH_SIZE | 50 (range 1–500) |
| KAFKA_BOOTSTRAP_SERVERS | localhost:29092 |
| USER_EVENTS_TOPIC | insurance.user.events (all three services) |
| USER_EVENTS_DLQ_TOPIC | insurance.user.events.dlq (Notification/Audit) |

Each consumer has a focused UserEventDecoder/UserEventListener, sharing its existing
stable logical consumer identity and Kafka group with its quote listener. Topic
partitions retain independent offsets. Marker `(eventId, consumerName)` and business
row commit together. Duplicates succeed without another row or marker. Unknown
nonempty types skip successfully. Existing quote behavior is unchanged.

Phase 17's DefaultErrorHandler/FixedBackOff applies: database/processing failures
retry (default three total attempts, fixed 1000 ms backoff); malformed supported
input and unsupported versions go directly to `insurance.user.events.dlq`.
The existing recovery callback retains original key/value bytes, partition and
allowlisted diagnostic headers. Offsets advance only after business success or
acknowledged DLQ recovery. Failed recovery leaves the source replayable. Notification
and Audit have independent groups and failures. Source/DLQ retain Phase 1 three
partitions, replication factor one; no new topic or retry topic is introduced.

Deferred: admin user status/role events, login/password events, refresh tokens,
asymmetric JWT, real messaging, DLQ replay, CDC/Schema Registry and Pricing events.
Phase 20 is not implemented.

User listeners and DLQ routing resolve `user.events.topic` and
`user.events.dlq-topic`; defaults preserve the Phase 1 topics. Set the same
`USER_EVENTS_TOPIC` in Auth, Notification and Audit when overriding. Provision
custom source/DLQ topics with matching partitions; applications do not create them.
