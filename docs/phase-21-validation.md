# Phase 21 — Admin Customer Management validation

Validated 2026-10-19 (Asia/Bangkok). Phase 21 only; Phase 22 is not started.

## Implementation report

1. **Files:** full created/modified manifest below. Production changes are Auth,
   focused Audit compatibility and required Gateway routing/security. Notification
   production code, Quote, Pricing and Angular are unchanged. Notification tests and
   documentation explicitly cover intentional status-event skipping.
2. **API contract:** GET /api/v1/admin/customers, GET /{id}, PATCH /{id}/disable,
   PATCH /{id}/enable. Status commands accept no body or `{}`; client actor/status
   fields are rejected. See [complete API contract](admin-customer-management.md).
3. **Authorization/scope:** Gateway and Auth independently require ADMIN. Auth uses
   the existing Pricing-style HS256/issuer/expiry/roles JWT validation pattern while
   retaining its encoder/key/login behavior. Missing/invalid bearer 401, CUSTOMER
   403, malformed ADMIN UUID 403. Targets must be CUSTOMER; ADMIN targets return
   409 CUSTOMER_REQUIRED for detail/status, including the caller's own ADMIN row.
   Missing targets return 404 CUSTOMER_NOT_FOUND. No role/admin-account management.
4. **Read behavior:** database CUSTOMER filter before pagination/counting; default
   page=0/size=20, page>=0 and size 1–100, createdAt DESC/id DESC. No complex search.
   List wrapper uses content/page/size/totalElements/totalPages. Safe view has id,
   email, role, status, createdAt, updatedAt; no User entity/password/security fields.
   Read operations produce no intent.
5. **Transitions:** ACTIVE→DISABLED emits UserDisabled; DISABLED→ACTIVE emits
   UserEnabled. Already-target status returns 200/current representation without
   changing updatedAt, inserting an intent or publishing a new business event.
6. **Concurrency:** User has no @Version, so repository findForUpdate uses
   PESSIMISTIC_WRITE on the existing row. The service transaction holds the lock
   through status+outbox commit. A waiter observes the committed status and becomes
   a no-op. No synchronized/in-memory lock, second publisher or migration.
7. **Events:** UserDisabled/UserEnabled v1 use the existing nine-field envelope,
   User aggregate, target UUID aggregateId/key and auth-service producer. Data is
   userId, canonical email, CUSTOMER role, resulting status, ADMIN changedBy UUID,
   changedAt. Distinct real transitions have distinct event IDs. UserRegistered v1
   and its decoder/data mapping remain unchanged. See [contract](user-status-events.md).
8. **Actor/correlation/time:** ADMIN UUID comes only from authenticated JWT security
   context. Existing AuthCorrelationId preserves valid inbound IDs up to 128 characters
   or generates a safe UUID fallback. One UTC microsecond Clock instant supplies
   occurredAt/changedAt/updatedAt/outbox created_at, only for genuine transitions.
9. **Transaction/outbox:** CustomerAdminService.setEnabled performs target lock,
   scope/no-op checks, status update/flush and existing OutboxRepository insert in
   one Auth transaction. A status-write failure creates no intent; an outbox failure
   rolls back status. Outer rollback removes both effects. No direct Kafka operation,
   AFTER_COMMIT send, new event-intent table, user scan or historical status backfill.
10. **Publication:** unchanged AuthOutboxPublisher sends the immutable stored JSON
    to configured insurance.user.events, keyed by userId. Existing bounded batch,
    advisory lock, no DB transaction across Kafka wait, acknowledgment-before-PUBLISHED
    and failure retry_count semantics remain. Ack followed by failed status update
    can duplicate identical eventId/key/JSON/correlation/time: at-least-once, not exactly-once.
11. **Audit:** existing user listener dispatches registration unchanged and status
    events to focused UserStatusEnvelope/UserStatusEventDecoder/recordUserStatus.
    Mapping is exact event type, ADMIN/changedBy actor, User/userId entity, preserved
    producer/correlation/occurredAt; metadata only role/status, without email. Existing
    processed_events identity remains (eventId, audit-service), with atomic business
    row+marker transaction. Different events for the same user are not deduplicated.
    Invalid supported statuses/versions reuse InvalidUserEventException and existing
    Phase 17 retry/user DLQ, original-byte/header and acknowledgment semantics.
12. **Notification:** unchanged production decoder intentionally skips both status
    types. No USER_DISABLED/USER_ENABLED notification, processed marker or DLQ for
    valid status events. Its group offsets advance normally. UserRegistered still
    produces its simulated welcome row and marker.
13. **Login regression:** real PostgreSQL and Gateway flow prove active login success,
    disabled future-login uniform 401 INVALID_CREDENTIALS / Invalid credentials,
    enabled login success again. Password hashes, default registration role/status,
    BCrypt cost and JWT issuance/claims/lifetime remain unchanged.
14. **Issued-token limit:** no revocation, blacklist, refresh-token revocation or
    session termination. A previously issued JWT still validates after disable,
    demonstrated against Auth bearer validation. Existing stateless Gateway/Quote
    authorization can accept it until expiry; Quote performs no remote Auth status
    check. This limitation is explicit in [security/API docs](admin-customer-management.md).

## Executed validation

15. **Tests added/results:** Auth HTTP tests cover all admin endpoints/roles,
    safe DTOs, pagination/order, missing/ADMIN targets, command idempotency, actor/
    correlation/time/payload, client-actor rejection, invalid issuer/expiry/signature/
    subject and no intent on failed status writes. Gateway tests cover all routes,
    independent 401/403 and bearer/correlation preservation. Audit status decoder
    tests cover valid types and safe permanent-input errors. Existing tests retained.
16. **Auth PostgreSQL — 7 passed:** four existing registration/outbox tests plus
    customer pagination/filter/detail, no read intents, status/login lifecycle,
    idempotency, unchanged password hash, failed status UPDATE, failed disable/enable
    outbox INSERT, outer rollback, missing/ADMIN target and real concurrent same-status
    requests. Two concurrent disables yield one UserDisabled; two enables yield
    one UserEnabled. Existing UserRegistered tests remain green. No migration.
17. **Auth Kafka — 1 extended integration test passed:** existing real broker
    pause/recovery registration case remains. Both status types publish exact stored
    JSON/userId key, share the registration partition, have distinct IDs and reach
    PUBLISHED. For each status type, Kafka acknowledgment is followed by an injected
    DB PUBLISHED-update failure: intent remains PENDING and a later poll resends
    byte-identical payload/key/event identity before successfully updating status.
18. **Audit Kafka — 3 passed:** existing Quote/Pricing/UserRegistered cases remain
    green. Extended user case adds UserDisabled/UserEnabled ADMIN/User mapping,
    changedBy attribution, role/status-only metadata, distinct rows/markers for
    legitimate lifecycle events, duplicate no-op, and direct user DLQ for unsupported
    status version with original bytes/safe headers. Audit PostgreSQL regression:
    12 passed. Notification Kafka: 2 passed, including valid status skips with no
    extra business rows/DLQ and preserved registration handling.
19. **End-to-end — passed:** real Gateway/Auth/Audit/Notification jars with disposable
    PostgreSQL/Kafka. Register temporary ADMIN-candidate and CUSTOMER, explicitly
    promote candidate locally then log in for ADMIN JWT. Customer-only list/detail,
    bounded pagination, 401/403 at Gateway and direct Auth, ADMIN target rejection
    and client actor rejection pass. Disable with publisher paused proves committed
    DISABLED+PENDING intent/source end=0. Enable publisher: two registrations and
    disable publish; Audit=3 and welcomes=2. Repeated disable creates no event; enable
    publishes another event, restores login, and repeat enable is a no-op. Core flow:
    four events/audit rows, two welcomes. Concurrent disable/enable pairs then create
    one extra event each, resulting in six legitimate events/audit rows/markers.
    Exact original status-event replay advances source total 6→7 while Audit stays
    at six rows/markers and Notification at two. Both groups catch up, user DLQ empty.
    [Topic descriptions and group offsets](phase-21-offset-evidence.txt): this run
    source ends `(1,6,0)`, DLQ `(0,0,0)`, Audit and Notification independently commit
    offsets 1/6 with zero lag; empty partition 2 may have no committed offset. Source/
    DLQ remain three partitions, replication factor one. Exact partition is not a
    contract. Consumer processing does not append to source; only the deliberate
    duplicate accounts for the seventh record. Temporary infrastructure is removed.
20. **Security — passed:** admin response field allowlist, explicit event DTO,
    authenticated actor, payload comparison, metadata-without-email, and application
    log scans. No password/hash, raw JWT/Authorization, refresh token, credentials or
    signing secret in admin responses/events/logs. Registered canonical email is
    expected admin/event data and is not logged. Login still legitimately returns
    an access token to its caller. DLQ retains original bytes under Phase 17 rules
    and must be treated as potentially sensitive.
21. **Maven:** full `./mvnw clean verify` passed **413 tests, zero failures/errors/skips**:
    Gateway 43, Auth 39, Quote 115, Pricing 124, Notification 43, Audit 49. Final Auth
    Kafka verification reran its 39 normal tests and extended Kafka test successfully.
    Relevant profile commands and disposable script are below. No Quote/Pricing/UI
    production changes or regression failures.
22. **Known limits/deferred:** existing tokens remain valid until expiry; no distributed
    account-status enforcement. Row-lock commands serialize per customer and may
    wait for competing transactions. Existing Auth publisher still requires session
    pooling/direct PostgreSQL, serializes batches and can be delayed by an oldest
    failing intent; no retention/cleanup or producer retry cutoff. Event/DLQ delivery
    remains at-least-once, with duplicate crash windows. No deletion, role editing,
    password reset, bulk/admin-account management, lockout, refresh/session security,
    status notifications, historical backfill, replay tooling or Phase 22 UI.
23. **Readiness:** Phase 21 implementation and required validation are complete and
    ready for Phase 22 — Audit UI. Phase 22 has not been started.

## Reproduction

```bash
./mvnw clean verify
./mvnw -pl services/auth-service,services/audit-service -Ppostgres-it verify
./mvnw -pl services/auth-service,services/audit-service,services/notification-service -Pkafka-it verify
python3 infrastructure/validation/phase21-e2e.py
```

Normal tests use the project's existing Mockito JVM-agent capability. Integration
profiles additionally require Docker. The [disposable script](../infrastructure/validation/phase21-e2e.py)
uses packaged jars, random ports, unique container names, no shared volumes and
finally cleanup. Its only manual ADMIN provisioning is in the temporary Auth DB,
following the existing local procedure. Shared durable databases, users, topics,
volumes and group offsets are untouched. No migration/checksum edits.

## Created/modified file manifest
- `docs/admin-customer-management.md`
- `docs/architecture.md`
- `docs/implementation-roadmap.md`
- `docs/kafka-design.md`
- `docs/phase-21-offset-evidence.txt`
- `docs/phase-21-validation.md`
- `docs/retry-and-dlq.md`
- `docs/security.md`
- `docs/specs/README.md`
- `docs/specs/phase-21-admin-customer-management.md`
- `docs/transactional-outbox.md`
- `docs/user-registered-event.md`
- `docs/user-status-events.md`
- `infrastructure/validation/phase21-e2e.py`
- `services/api-gateway/README.md`
- `services/api-gateway/src/main/java/com/insurance/platform/gateway/config/GatewaySecurityConfiguration.java`
- `services/api-gateway/src/main/resources/application.yml`
- `services/api-gateway/src/test/java/com/insurance/platform/gateway/ApiGatewayApplicationTests.java`
- `services/audit-service/README.md`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/UserEventListener.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/UserStatusEnvelope.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/UserStatusEventDecoder.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/service/AuditService.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditKafkaIT.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/UserStatusEventTests.java`
- `services/auth-service/README.md`
- `services/auth-service/pom.xml`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/AuthJwtDecoderConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/AuthSecurityConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/controller/CustomerAdminController.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/dto/CustomerPage.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/dto/CustomerResponse.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/dto/CustomerStatusCommand.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/entity/User.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/event/AuthAdminIdentity.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/event/UserStatusEventData.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/exception/ApiExceptionHandler.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/repository/UserRepository.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/service/CustomerAdminService.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthAdminJwtSupport.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthKafkaIT.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthPostgresIT.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthTestIdentity.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/CustomerAdministrationTests.java`
- `services/notification-service/README.md`
- `services/notification-service/src/test/java/com/insurance/platform/notification/NotificationKafkaIT.java`
- `services/notification-service/src/test/java/com/insurance/platform/notification/UserEventTests.java`
