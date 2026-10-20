# Admin customer management — Phase 21

Auth now independently validates the same HS256 bearer JWT/issuer/expiry/roles
convention as Gateway and Pricing. Every endpoint below requires an authenticated
ADMIN with a UUID subject. Missing/invalid bearer credentials return 401; CUSTOMER
returns 403. A malformed ADMIN UUID is rejected safely with 403, without mutation.
Gateway forwards the original bearer and X-Correlation-ID; it independently requires
ADMIN too. Auth's existing register/login endpoints and JWT issuing behavior remain.

## API

Use Gateway at `http://localhost:8080`; direct Auth remains available for independent
security tests. Provision a local ADMIN using Auth README's existing explicit
registration/database-promotion/login procedure. No admin-creation API is added.

| Method | Path | Result |
| --- | --- | --- |
| GET | /api/v1/admin/customers?page=0&size=20 | CUSTOMER-only page |
| GET | /api/v1/admin/customers/{id} | Safe CUSTOMER detail |
| PATCH | /api/v1/admin/customers/{id}/disable | ACTIVE → DISABLED |
| PATCH | /api/v1/admin/customers/{id}/enable | DISABLED → ACTIVE |

Pagination defaults page=0, size=20; page must be nonnegative and size 1–100.
Ordering is createdAt DESC, id DESC. Filtering is applied in the database by
role=CUSTOMER before pagination/counting. There is no email search or additional
filter in this phase. Out-of-range pages are empty. Pagination is not a snapshot
across concurrent writes.

List shape: `{content, page, size, totalElements, totalPages}`. Each content item
and detail/status response has exactly these safe fields:

```json
{
  "id": "22410825-5c00-4df3-9598-f79833ee28eb",
  "email": "customer@example.com",
  "role": "CUSTOMER",
  "status": "ACTIVE",
  "createdAt": "2026-10-19T00:00:00Z",
  "updatedAt": "2026-10-19T00:00:00Z"
}
```

No entities, password/hash, JWT details, refresh tokens or internal security data
are returned. PATCH requires no body; `{}` is also accepted. Body fields such as
changedBy/adminId/actorId/status are rejected with 400, not trusted for attribution.

Repeated disable on DISABLED or enable on ACTIVE returns 200/current representation,
without changing updatedAt, creating an intent or sending an event. GET list/detail
never produce events. Commands do not delete accounts, edit roles or reset passwords.

| Error | Code / policy |
| --- | --- |
| 400 | INVALID_REQUEST: pagination, UUID or command-body input |
| 401 | UNAUTHORIZED: absent/invalid bearer |
| 403 | FORBIDDEN: non-ADMIN or malformed authenticated ADMIN UUID |
| 404 | CUSTOMER_NOT_FOUND: target absent |
| 409 | CUSTOMER_REQUIRED: target is ADMIN, for detail and status commands |
| 500 | INTERNAL_ERROR: safe response; transaction rolls back |

ADMIN accounts are outside managed scope, including the caller's own ADMIN row.
No special self-disable bypass or role-management endpoint exists.

## Transaction and concurrency

CustomerAdminService.setEnabled owns one Auth transaction. It captures the actor
from Spring Security, selects the target with PESSIMISTIC_WRITE (`SELECT ... FOR
UPDATE`), verifies CUSTOMER scope, and checks current state while the row lock is
held. A same-state command returns without a write. A real transition captures one
injected UTC microsecond instant, updates status/updatedAt, flushes, and inserts its
immutable UserDisabled/UserEnabled intent through the existing OutboxRepository.
The row lock lasts until commit/rollback. No database transaction spans Kafka I/O.

Two same-transition commands serialize on the database row. The waiter reads the
committed resulting state and becomes a successful no-op: exactly one real change
and intent. No in-memory lock, new version column or migration is needed. A failed
status write creates no intent; outbox failure rolls back the status write. Outer
transaction rollback also removes both effects. Existing outbox publisher/locking,
acknowledgment and retry behavior is unchanged.

## Events and consumers

See [UserDisabled/UserEnabled v1](user-status-events.md). They use the existing user
topic/userId key, outbox, Audit idempotency and Phase 17 retry/user DLQ. Audit maps
ADMIN actor/changedBy and User entity/userId; metadata holds role/status without
email. Notification intentionally skips the two status types, creates no message
or marker, and advances its own source offsets. UserRegistered remains unchanged.
No historical status backfill or startup scan runs.

```text
ADMIN
  ↓ Gateway
PATCH customer disable
  ↓
Auth transaction
  ├── users.status = DISABLED, updated_at = operation time
  └── UserDisabled outbox PENDING
  ↓
COMMIT → HTTP 200
  ↓ later
existing Auth Outbox Publisher
  ↓
insurance.user.events (key = userId)
  ├── Audit → ADMIN / User / UserDisabled
  └── Notification → intentional skip
```

## Login and issued-token limitation

An ACTIVE customer can log in. After disabling, future login attempts return the
existing uniform `401 INVALID_CREDENTIALS` / `Invalid credentials`; enabling permits
login again. Password hashes and token issuance/expiry behavior are unchanged.

**Already-issued JWTs remain valid until expiry under the existing stateless model.**
Disabling is not token revocation. Gateway, Auth bearer validation and Quote do not
remotely check current account status for every request. A disabled customer's
existing JWT may therefore still authorize Quote requests until expiry. No blacklist,
refresh-token revocation, session termination or remote Auth lookup is implemented.

See [Phase 21 specification](specs/phase-21-admin-customer-management.md) and
[validation](phase-21-validation.md). No admin UI or Phase 22 work is included.
