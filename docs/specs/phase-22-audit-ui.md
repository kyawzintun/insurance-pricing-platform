# Phase 22 — Audit UI

Scope: ADMIN audit browsing only. Phase 23 (Refresh Tokens) is not started.

```text
Angular Audit UI
      ↓
Gateway
      ↓
Audit Service
      ↓
audit_records
      ↓
server-side filters + pagination
```

## API and security

- `GET /api/v1/admin/audit`: `content`, `page`, `size`, `totalElements`, `totalPages`.
- `GET /api/v1/admin/audit/{id}`: one record, or safe 404.
- Both Gateway and Audit independently validate the existing HS256 signature, issuer,
  expiry, subject and roles, and require ADMIN. Missing/invalid JWT: 401; CUSTOMER: 403.
- Audit permits only GET for admin reads. POST/PUT/PATCH/DELETE are denied, including
  for ADMIN. No mutation endpoints exist. Responses use `Cache-Control: no-store`.
- Supply `AUTH_JWT_SECRET` and `AUTH_JWT_ISSUER` consistently with Auth/Gateway.
  The existing public development key is available only in the local profile.

## Query contract

`page` defaults to 0; `size` defaults to 20 and must be 1–100. Pages are nonnegative;
requests beyond JPA's supported integer offset return safe 400. Out-of-range pages
otherwise return empty content with the actual count.

Exact filters: `eventType`, `actorType`, `actorId`, `entityType`, `entityId`,
`correlationId`, `producer`, `eventId`. IDs must be canonical UUIDs; correlation IDs
are arbitrary exact strings up to 128 characters. Other text lengths follow stored
column limits. Blank filters are ignored. All filters combine with AND. No wildcard,
substring or SQL/free-text search is supported.

The primary time is **occurredAt**, consistently for display, filtering and ordering.
`from` and `to` accept ISO-8601 UTC instants, with `occurred_at >= from` and
`occurred_at < to`. `from > to` returns 400; equal endpoints produce an empty range.
Ordering is `occurred_at DESC, id DESC`. This intentionally reuses existing
`occurred_at` and `(event_type, occurred_at)` indexes and entity lookup index.
No previous migrations changed and no migration was necessary. Spring Data JPA
Specifications bind values, filter/count/order/page in PostgreSQL. No in-memory
full-history filtering occurs. Other filter combinations can require scans; offset
pagination/counts can become expensive for deep history. No snapshot across pages
is promised while new events arrive; cursor pagination and extra indexes are deferred
until measurement demonstrates a need.

Rows expose only id, eventId, eventType, actorType, actorId, entityType, entityId,
correlationId, producer, occurredAt and createdAt. Detail adds parsed `metadata` JSON.
Stored compact producer allowlists remain the safety boundary; no Kafka headers,
credentials or JWTs are added. The consumer/outbox/retry/idempotency code is unchanged.

## Angular behavior

`/admin/audit` and `/admin/audit/:id` require an active ADMIN session. The Audit
navigation entry is hidden for CUSTOMER. Unauthenticated/expired sessions redirect
to login; CUSTOMER redirects to dashboard, following existing guard conventions.
Backend enforcement remains authoritative.

Reactive Forms own filter inputs and page-size selection. Apply trims and validates
IDs/UTC ranges; Clear resets filters/page/size. Requests occur on Apply, pagination,
URL navigation and explicit retry, never every keystroke. Useful filters/page/size
are synchronized to query parameters, preserved into detail and on the Back link.
NgRx owns applied query, paginated list, detail, loading/errors. Effects switch/cancel
superseded reads. Session changes reset state and cancel requests. Signals own local
validation and metadata visibility. No token persistence or refresh behavior changes.
Browser refresh still requires login under the existing memory-only session design.

The semantic table scrolls horizontally on narrow screens. Previous/Next controls
use server totals. List supports loading, empty, safe errors and retry. Details show
event, actor, entity, tracing and formatted metadata, with explicit empty/not-found
states. Correlation ID links to an exact-match history search. JSON is rendered by
Angular interpolation/JsonPipe, never innerHTML; HTML-looking strings remain text.

## Validation

See [Phase 22 validation](../phase-22-validation.md). Reproducible real-service API
validation is `python3 infrastructure/validation/phase22-e2e.py` after `./mvnw package`.
It provisions unique disposable containers and random ports, creates real events and
cleans up only its own resources. Notification is unnecessary for audit reads.

No export, editing, deletion, retention, streaming, alerts, full-text engine, DLQ UI,
service authentication redesign or refresh tokens are included.
