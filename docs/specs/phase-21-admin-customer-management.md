# Phase 21 — Admin Customer Management

Implement ADMIN-only customer list/detail/disable/enable at
/api/v1/admin/customers, independently authorized by Gateway and Auth. Bounded
page/size pagination and explicit safe DTOs; CUSTOMER accounts only. Missing target
404, ADMIN target 409 CUSTOMER_REQUIRED. Same-state status commands succeed without
writes/events. No arbitrary client actor/status fields.

Use PostgreSQL row locking on the existing User schema to serialize status commands.
A genuine status change and UserDisabled/UserEnabled v1 outbox intent share one
Auth transaction. Capture authenticated ADMIN UUID, preserved correlation and one
UTC operation time. Existing generic Auth publisher handles the immutable intent.
No schema migration or historical backfill.

Audit adds focused status decoding and ADMIN/User mappings with role/status metadata,
using existing processed_events and retry/user DLQ. UserRegistered is unchanged;
Notification intentionally skips status events and sends no status notifications.

Validate safe APIs/401/403, CUSTOMER scope, pagination, idempotency, mutation/outbox
rollback, concurrent same-transition correctness, login disable/enable regression,
real Kafka key/bytes/partition/duplicate/DLQ behavior and disposable Gateway flow.
Document that already-issued stateless JWTs remain valid until expiry; no Quote
remote status check or token revocation is added.

See [API contract](../admin-customer-management.md), [events](../user-status-events.md)
and [validation](../phase-21-validation.md). No deletion, roles, passwords, bulk/admin
account management, refresh tokens, UI, replay framework or Phase 22 work.
