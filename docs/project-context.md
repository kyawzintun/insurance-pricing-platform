# Project Context

Status: Approved high-level context recorded from the Phase 0 request; detailed planning pending.

Insurance Pricing Platform is a learning project for a simple car-insurance pricing platform, not realistic actuarial insurance pricing.

Planned technologies: Angular + NgRx, Spring Boot 4, Spring Security, Kafka, PostgreSQL, Docker, and microservices. The main learning targets are Spring Boot/microservices, Kafka, Angular + NgRx, PostgreSQL, and specification-first AI-assisted SDLC.

The main users are CUSTOMER and ADMIN. Work proceeds incrementally, phase by phase, in one monorepo. Phase 0 provides only repository and documentation foundations; no application implementation has started.

Detailed approved planning notes have not yet been supplied. Future specifications should capture agreed behavior before implementation; unresolved details must not be treated as approved requirements.

Phase 1 adds shared local PostgreSQL and Kafka infrastructure only. Application code remains unimplemented; Phase 2 has not started.

Phase 2 adds six startup-only Spring Boot 4 Maven applications. No business implementation exists. Phase 3 (Database Foundation and Flyway) has not started.

Phase 3 provides service-owned Flyway schemas and fixed educational seeds only. Phase 4 — Authentication Basics has not started.

Phase 4 implements Auth Service registration/login and JWT issuance only. Phase 5 — API Gateway and JWT Validation has not started.

## Phase 5 — Gateway Authentication

Gateway now routes `/api/v1/auth/**` to configurable Auth Service URL (default localhost:8081). POST register/login and GET health/info are public; other requests require a validated HS256 JWT. The local profile shares Auth's public development key; outside local both services require a configured secret. Subject and roles are parsed, with no business role authorization. Authorization is forwarded unchanged; downstream token validation remains future work. Minimal configurable CORS and bounded correlation IDs are implemented. No business routes, messaging, schema changes, or discovery were added. See [Gateway details](../services/api-gateway/README.md) and [validation](phase-5-validation.md). Earlier phase sections describe historical milestones; current status is Phase 15 complete.

## Phase 6 — Internal Pricing Calculation

Pricing Service exposes POST `/internal/v1/pricing/calculate` directly on port 8083. Its conventional controller/service/repository/DTO layers read the existing pricing_rules table, derive ages with a UTC Clock, and return a deterministic THB breakdown with rule IDs/versions. The calculation is read-only; schemas, seeds, outbox, and Kafka behavior are unchanged. The endpoint is temporarily unauthenticated, stays off Gateway, and is intended for private/internal access only. Service-to-service authentication is deferred to Phase 25; this is not production-secure. No admin CRUD or Quote integration was added. See [Pricing semantics and limitations](../services/pricing-service/README.md).

## Phase 7 — Pricing Administration

Gateway routes only `/api/v1/admin/pricing/**` to configurable Pricing URL (default localhost:8083). Gateway and Pricing independently verify HS256 JWTs and require ADMIN; no user identity headers are trusted. Pricing owns rule list/get/create/full update/enable/disable APIs with shared Phase 6 validation and existing @Version optimistic locking. Writes affect only pricing_rules. No schemas, seeds, Kafka/outbox behavior, or unrelated services changed. Internal calculation remains temporarily unauthenticated directly on Pricing, not routed by Gateway; service-to-service authentication is still deferred to Phase 25. No Angular admin UI or Quote integration. See [admin guide](../services/pricing-service/README.md) and [validation](phase-7-validation.md).

## Phase 8 — Quote Service MVP

CUSTOMER can POST `/api/v1/quotes` through Gateway. Quote independently validates HS256 signature, expiration, issuer, UUID subject, and roles; customer_id comes only from sub. ADMIN-only cannot create quotes. Gateway forwards the Bearer token without trusted identity headers. Quote validates input and active, matching brand/model references in quote_db and snapshots their names.

Quote synchronously calls Pricing directly with only its seven calculation inputs, no customer JWT/identity, and bounded connection/read timeouts. **Quote → Pricing uses internal REST without service credentials; service-to-service authentication remains deferred to Phase 25.** After a usable response, a separate transaction stores the complete PRICED aggregate and 30-day expiration. Pricing failure creates no partial quote or FAILED row. Existing premiums and names are historical snapshots. No cross-service database reads, schema/seed changes, outbox writes, or Kafka messages were introduced.

No retrieval/history, ownership retrieval rules, editing, repricing, or expiration scheduler exists yet. See [Quote specification](specs/phase-8-quote-service-mvp.md), [service guide](../services/quote-service/README.md), and [validation](phase-8-validation.md). Phase 9 is not started.

## Phase 9 — Quote Retrieval and Ownership

GET `/api/v1/quotes/{id}` and GET `/api/v1/quotes` return stored snapshots. Quote independently requires CUSTOMER/ADMIN. CUSTOMER detail/list queries include JWT-sub ownership; another owner's quote and a missing quote return identical 404s. ADMIN can retrieve/list all owners. No customerId override, optional filters, or Gateway-only authorization. POST remains CUSTOMER-only.

Pagination defaults to page 0 / size 20 (maximum 100), fixed creation-descending/ID-descending order. Read-only transactions map stored data; scalar ID pagination followed by an unpaged snapshot entity graph avoids collection-fetch pagination and N+1 queries. No Pricing/current catalog dependency, status transition, migration, outbox write, or Kafka event. Live validation used temporary application ports and cleaned up its processes/data. See [Phase 9 validation](phase-9-validation.md) and [Quote guide](../services/quote-service/README.md). Phase 10 is documented below.

## Phase 10 — Angular Foundation

Standalone Angular provides registration/login and a protected dashboard placeholder. Reactive Forms own values and validation; component Signals own loading, safe errors, and password visibility; NgRx Store owns token, user ID, roles, expiry, and authentication state. Effects navigate after login/logout. Credentials never enter NgRx actions/state.

All HTTP goes through the configurable Gateway base URL. Bearer attachment is limited to its API paths and excludes login/register. Protected-request 401 clears the matching current session without refresh/retry. Guards and decoded JWT claims support UI only; backend authorization remains authoritative. Logout clears frontend memory without revoking tokens. Browser reload intentionally logs out. No signing secrets or token persistence are included. Quote UI and refresh tokens are deferred. Backend production code, schemas, and infrastructure remain unchanged. See [frontend guide](../frontend/README.md) and [validation](phase-10-validation.md).

## Phase 11 — Customer Quote Frontend

CUSTOMER-only frontend quote routes provide creation, paginated owned history and stored detail. Reactive Forms own inputs; local Signals own catalog/expansion state; NgRx Effects/Store own shared quote results, pagination and request outcomes. Session changes clear quote state and cancel pending responses. All calls use Gateway with the existing Bearer interceptor; backend ownership remains authoritative. No customerId is supplied by Angular.

Quote-owned active vehicle references are exposed as read-only id/name DTOs through authenticated Gateway routes. CUSTOMER and ADMIN tokens may read the reference APIs; the frontend workflow is CUSTOMER-only. Missing/inactive brands have the same 404. No cross-service database reads, migrations, new tables, outbox writes or messaging. Detail renders historical snapshots without current Pricing/catalog requests. Date-only birth dates preserve their calendar day. See [Phase 11 validation](phase-11-validation.md).

## Phase 12 — QuotePriced direct producer

Quote Service registers an explicit DTO event inside persistence and sends it to the existing quote topic only after successful commit. The key is quote ID; metadata captures UTC occurrence time and the same safe correlation-ID convention as Gateway. The compact payload excludes credentials and personal driver details. Safe producer failure logging does not change HTTP 201 or stored PRICED state. No cross-service schema changes, consumers, outbox writes or retry/DLQ processing. Direct publishing has an intentional dual-write failure window until Phase 16. See [event contract](quote-priced-event.md) and [validation](phase-12-validation.md).

## Phase 13 — Notification Consumer

Notification Service consumes QuotePriced v1 from `insurance.quote.events` in its dedicated configurable `notification-service` group. It writes only simulated CREATED notifications in its own database, with no external HTTP/provider calls. Consumer-owned DTOs match the unchanged Phase 12 wire contract. Earliest offset reset deliberately supports historical learning records; committed offsets take precedence on restart. RECORD acknowledgment follows the local database transaction commit. Invalid supported events, unsupported versions and persistence failures stop consumption without skipping the failed offset; unknown event types are intentionally logged/skipped. Existing UNIQUE(event_id) is preserved but is not full idempotency. No processed_events, retry/DLQ, Audit consumer or outbox logic is implemented. See [Notification guide](../services/notification-service/README.md), [specification](specs/phase-13-notification-consumer.md), and [validation](phase-13-validation.md).

## Phase 14 — Independent Audit consumer

Audit now consumes the unchanged QuotePriced v1 envelope using its own configurable `audit-service` group, separate from `notification-service`. Both groups independently receive the same records. Audit writes only audit_records, with CUSTOMER/customerId attribution, Quote/quoteId entity identity, preserved correlation/occurrence data, and compact allowlisted JSONB metadata. RECORD offset commits follow local DB commit; unsupported types are skipped and invalid supported events/database failures stop the container. Earliest supports historical learning events. UNIQUE(event_id) remains visible without processed_events/idempotency, retry/DLQ or Outbox logic. A necessary Audit-only V3 widens correlation_id to 128 to match the existing producer contract, preserving V1/V2 checksums. See [Audit guide](../services/audit-service/README.md), [specification](specs/phase-14-audit-consumer.md) and [validation](phase-14-validation.md).

## Phase 15 — Idempotent Notification and Audit consumers

Both consumers now use their own existing processed_events tables, keyed by eventId and stable logical consumer name (notification-service / audit-service). An atomic ON CONFLICT insert claims the event inside the same local JPA transaction as its business write. Conflict returns DUPLICATE successfully, without rewriting the business row or original processed_at; RECORD acknowledgment may then advance the offset. Concurrent attempts are serialized by PostgreSQL uniqueness. A replay of a pre-Phase-15 business row lazily establishes its missing marker only after the atomic claim, without bulk backfill or rewriting the row. Genuine failures preserve stop/uncommitted-offset behavior. No schema, producer contract, group/topic, retry/DLQ or Outbox changes. See [specification](specs/phase-15-idempotent-consumers.md) and [validation](phase-15-validation.md).

## Phase 16 — Quote Transactional Outbox

Quote creation now stores the aggregate and a complete QuotePriced v1 JSONB envelope in the same transaction. The direct after-commit publisher is removed. HTTP 201 means durable quote/event intent; scheduled polling publishes later with the same event ID, correlation, occurrence time, topic and quote UUID key. An autocommit connection with a shared PostgreSQL session advisory lock excludes competing dispatchers without holding a transaction during Kafka I/O. Acknowledged sends become PUBLISHED; failed attempts remain PENDING and increment retry_count. Duplicate publication remains possible and Phase 15 consumers handle it unchanged.

Quote V6 widens only outbox correlation_id to 128 to match the existing contract; no other schema or seed changes, historical backfill, Auth/Pricing outbox, or Phase 17 retry/DLQ policy. The preceding phase sections describe historical milestones; this section supersedes their producer limitations. See [design and operational limits](transactional-outbox.md), [specification](specs/phase-16-transactional-outbox.md), and [validation](phase-16-validation.md).
