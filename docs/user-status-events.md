# UserDisabled and UserEnabled v1 — Phase 21

Only genuine CUSTOMER status transitions create these events. UserRegistered v1
is unchanged. All three types share the existing Auth transactional outbox, publisher,
`insurance.user.events` topic and userId Kafka key. Source/DLQ names still resolve
USER_EVENTS_TOPIC / USER_EVENTS_DLQ_TOPIC with existing defaults. No new topics,
partition changes or publisher are introduced.

Example UserDisabled:

```json
{
  "eventId": "6105422e-0262-44b1-8177-82bfc00ec58b",
  "eventType": "UserDisabled",
  "eventVersion": 1,
  "aggregateType": "User",
  "aggregateId": "22410825-5c00-4df3-9598-f79833ee28eb",
  "occurredAt": "2026-10-19T00:00:00Z",
  "correlationId": "admin-customer-command",
  "producer": "auth-service",
  "data": {
    "userId": "22410825-5c00-4df3-9598-f79833ee28eb",
    "email": "customer@example.com",
    "role": "CUSTOMER",
    "status": "DISABLED",
    "changedBy": "00000000-0000-0000-0000-000000000001",
    "changedAt": "2026-10-19T00:00:00Z"
  }
}
```

UserEnabled has eventType=UserEnabled and status=ACTIVE. Both have a new UUID eventId,
version 1, User aggregate, target user UUID as aggregateId/data.userId, auth-service
producer and six allowlisted data fields. Email is canonical; names, password/hash,
JWT, Authorization, refresh tokens, signing keys and credentials are absent. Email
is never a Kafka key/header or logged payload. Audit omits it from metadata.

changedBy comes from the authenticated ADMIN JWT UUID, never client input. The
request correlation ID is preserved if it matches `[A-Za-z0-9._-]{1,128}`; direct
calls get a UUID fallback. One injected UTC microsecond instant supplies occurredAt,
changedAt, users.updated_at and outbox created_at. Time/event identity is captured
only on a real transition and never rebuilt during publication.

Status and outbox intent commit in one transaction under a customer row lock.
Failure rolls both back. Idempotent repeats and read operations create no event.
No migration, user history rewrite or historical status backfill is introduced.

The existing publisher sends exact stored JSONB text keyed by userId, waits for
broker acknowledgment, then marks PUBLISHED. Failed sends remain PENDING and use
the established retry_count policy. Broker acceptance followed by failed status
update/crash can resend identical bytes/eventId/correlation/time. This is at-least-once,
not exactly-once. Same-user records use the same partition; offsets need not be
consecutive, and duplicate delivery remains possible.

Audit's user listener dispatches registration to its unchanged decoder/service and
status events to focused UserStatusEnvelope/UserStatusEventDecoder handling.
Validation requires the supported v1 type, UUID identities, matching aggregate,
CUSTOMER role, type-appropriate status, ADMIN changedBy UUID, canonical email and
matching occurredAt/changedAt. Mapping is exact event type, ADMIN/changedBy actor,
User/userId entity, preserved occurrence/correlation/auth-service producer, and
metadata `{role, status}`. Each eventId gets its own audit row and processed marker;
userId is not a deduplication key. Duplicate status delivery succeeds without another
row/marker. Marker and business row share the existing local transaction.

Invalid supported status events use InvalidUserEventException, the existing bounded
retry/classification/recovery design and `insurance.user.events.dlq`. Original bytes,
partition and safe diagnostic headers are preserved. Recovery acknowledgment gates
source completion; failed recovery leaves source replayable. Unknown valid types
still skip. Notification supports only UserRegistered and intentionally skips both
status types without a notification, processed marker or DLQ record.

See [API/concurrency/security details](admin-customer-management.md),
[UserRegistered v1](user-registered-event.md) and [validation](phase-21-validation.md).
No UserUpdated/UserDeleted/login/logout events, status notifications, token revocation,
admin-account management or Phase 22 work is included.
