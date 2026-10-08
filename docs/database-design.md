# Database Design

Status: Approved database boundaries recorded; detailed design pending migration from approved planning notes.

All backend services will use PostgreSQL. Each microservice will own its own logical database. Cross-service database access is prohibited.

`infrastructure/postgres/` is reserved for shared PostgreSQL setup and initialization. Future service-owned Flyway migrations must live in the owning Spring Boot service, not under `infrastructure/`.

Database schemas, tables, connections, and migrations are not defined in Phase 0. Transactional Outbox will be added later; its schema is not specified here.

## Phase 1 — Local Databases

The shared PostgreSQL container initializes `auth_db`, `quote_db`, `pricing_db`, `notification_db`, and `audit_db`. These are owned logically by the respective future services. No business tables or service migrations are created.

`infrastructure/postgres/init-databases.sql` runs only on first initialization of an empty data volume, following the [official PostgreSQL image behavior](https://hub.docker.com/_/postgres). Normal restarts preserve databases. A shared development bootstrap account initializes them; per-service access enforcement remains future work, and cross-service database access remains prohibited.

## Phase 2 — Connectivity Baseline

All five backend applications connect to their assigned database using environment-driven local configuration. Hibernate DDL is disabled (`ddl-auto: none`), SQL initialization is disabled, and there are no entities, repositories, or migrations. Flyway remains enabled and starts cleanly with zero migrations; it automatically creates an empty `flyway_schema_history` metadata table in each database. No business tables were created. Service-owned migrations remain for Phase 3.

## Phase 3 — Service-Owned Flyway Schemas

Flyway is the schema source of truth. Each service loads its own `src/main/resources/db/migration/` directory. Hibernate remains `ddl-auto: none`; no entities, repositories, business APIs, or message-processing code exist.

| Database | Application tables | Migrations |
| --- | --- | --- |
| `auth_db` | `users`, `refresh_tokens`, `outbox_events` | V1 users; V2 refresh tokens; V3 outbox |
| `quote_db` | `vehicle_brands`, `vehicle_models`, `quotes`, `quote_drivers`, `quote_vehicles`, `pricing_breakdowns`, `pricing_adjustments`, `outbox_events` | V1 catalog; V2 quotes/driver/vehicle; V3 pricing snapshots; V4 outbox; V5 catalog seed |
| `pricing_db` | `pricing_rules`, `outbox_events` | V1 rules; V2 outbox; V3 pricing seed |
| `notification_db` | `notifications`, `processed_events` | V1 notifications; V2 processed events |
| `audit_db` | `audit_records`, `processed_events` | V1 audit records; V2 processed events |

Each database also contains its own `flyway_schema_history`. Applied migrations are immutable: add a new numbered migration for subsequent schema or seed changes, rather than editing an applied file or using Flyway repair to hide checksum changes.

### Constraints and Indexes

Primary keys use application-supplied UUIDs, except `processed_events`, whose key is `(event_id, consumer_name)`. Required fields are NOT NULL. Email, quote references, catalog names, per-quote driver/vehicle/breakdown rows, and relevant event IDs have uniqueness constraints.

Foreign keys exist only for Auth refresh-token ownership and Quote's catalog/model, quote/driver, quote/vehicle, quote/breakdown, and breakdown/adjustment relationships. Delete behavior uses PostgreSQL's default NO ACTION; no cascade policy is invented. Cross-service IDs (`customer_id`, `pricing_rule_id`, notification references, and audit identifiers) have no foreign keys. Quote vehicle catalog IDs/names remain unconstrained historical snapshots.

Checks enforce the supplied Auth role/status values, non-negative experience/claims, monetary amounts/factors, retry counts, sequence numbers, and rule versions; vehicle value, engine size, manufacturing year, and event version must be positive. Other workflow statuses and pricing operators/types remain strings, pending later behavior specifications.

Indexes support refresh-token user/hash lookup, customer quote history, status/expiry lookup, pricing-breakdown adjustments, outbox status/time, active pricing rules, and audit entity/time/event lookup. The `(brand_id, name)` unique index also serves brand-only lookup. The `(event_type, occurred_at DESC)` audit index serves event-only lookup, avoiding redundant single-column indexes.

### Seed Data and Assumptions

Quote V5 adds four active brands and twelve models: Toyota (Yaris, Corolla, Camry), Honda (City, Civic, Accord), Mazda (Mazda 2, Mazda 3, CX-5), and Nissan (Almera, Sylphy, X-Trail).

Pricing V3 adds the seven requested educational rules: base premium 8000.00; age under 25 ×1.25; experience under 3 ×1.15; vehicle age over 10 ×1.20; previous claims at least 2 ×1.30; comprehensive ×1.40; third-party ×1.00. They are enabled with version 0 and effective from `2026-01-01T00:00:00Z`. Phase 3 supplied data only; Phase 6 now implements the calculation semantics documented in [Pricing Service](../services/pricing-service/README.md).

Seed UUIDs are fixed literals derived once from a stable namespace; timestamps are fixed for reproducible rebuilds. `BASE_PREMIUM` uses `EQUALS` with no comparison operand because the required operator column is non-null; this is a storage placeholder for an unconditional base amount, not an implemented evaluation rule. Email uniqueness follows PostgreSQL's ordinary case-sensitive VARCHAR behavior; Phase 4 now canonicalizes email in Auth before persistence/lookup and checks case-insensitively; direct database writes must preserve that convention. IDs and timestamps have no automatic generation/update triggers. Quote expiry is stored but no expiration scheduler or default is added.

Outbox and processed-event tables are schema preparation only: no publishing, retries, deduplication, or consumer behavior has been implemented. There are no user/account/password seeds.

Phase 4 maps the existing `users` table to a JPA User entity without changing any migration. The refresh-token and outbox tables remain unused by Auth.

## Phase 6 — Read-only Pricing Mapping

PricingRule maps the existing pricing_rules columns, including enum type/operator and optimistic-lock version. PricingRuleRepository exposes only enabled/effective rule lookup. Calculation uses a read-only transaction and makes no writes to any table. No migration or seed value changed. Base and adjustment rule IDs/versions are returned in DTOs for future quote snapshots; no snapshot is persisted in Phase 6.

## Phase 7 — Versioned Pricing Administration

Admin writes are restricted to pricing_rules. Existing @Version protects SQL UPDATEs; expected-version checks and concurrent optimistic-lock failures return 409. IDs and timestamps are generated by the server; updates preserve createdAt. No schema/migration changes, seed edits, physical-delete endpoint, or outbox writes. Effective/timestamp values use PostgreSQL microsecond precision. Existing multiple-rule selection remains unchanged; no global single-base constraint is introduced. Isolated PostgreSQL tests cover real version increments and simultaneous-write conflicts.
