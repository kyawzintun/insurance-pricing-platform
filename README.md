# Insurance Pricing Platform

A learning project for practicing Angular + NgRx, Spring Boot 4, Spring Security, Kafka, PostgreSQL, Docker, microservices, and specification-first AI-assisted SDLC.

The project focuses on a simple car-insurance pricing platform. It is not intended to implement realistic actuarial insurance pricing. The main learning targets are Spring Boot/microservices, Kafka, Angular + NgRx, PostgreSQL, and specification-first AI-assisted development.

## Current Project Status

**Phase 17 — Retry and DLQ: Complete**

Phase 0 is complete. Phase 1 shared PostgreSQL and Kafka infrastructure has passed startup, connectivity, initialization, and persistence validation. Phase 2 added six Maven-based service skeletons. Phase 3 adds service-owned Flyway schemas and educational seed data. Phase 4 implements registration and login in Auth Service only. Phase 5 adds Gateway Auth routing and JWT validation. Phase 6 adds internal database-driven premium calculation. Phase 7 adds ADMIN-only pricing-rule administration. Phase 8 adds CUSTOMER quote creation and stored pricing snapshots. Phase 9 adds secure quote retrieval and pagination. Phase 10 adds Angular registration/login and a protected dashboard foundation. Implementation will proceed phase by phase.

## Main Users

- **CUSTOMER**
- **ADMIN**

## Planned Features

Customer registration/login, Gateway JWT validation, internal pricing, ADMIN pricing-rule management, CUSTOMER quote creation with stored pricing breakdowns and a 30-day expiresAt, and secure CUSTOMER/ADMIN retrieval are implemented (Phases 4–9). The following features remain planned:

- Kafka-based notifications
- Kafka-based audit logging
- Automatic quote expiration processing (the 30-day timestamp is stored now)

## Planned Architecture

- **Angular + NgRx** frontend
- **API Gateway**
- **Auth Service**
- **Quote Service**
- **Pricing Service**
- **Notification Service**
- **Audit Service**
- **Kafka** for asynchronous domain events
- **PostgreSQL** for all backend services

REST will be used for synchronous communication when an immediate response is needed. Each microservice will own its own logical database, with no cross-service database access. Transactional Outbox will be introduced in a later phase. PostgreSQL and Kafka are configured for local development. Auth Service implements registration and login. Gateway routes Auth, Pricing administration, and Quote requests. Pricing calculates premiums and manages rules; Quote creates priced snapshots. Notification consumes QuotePriced events and stores simulated notifications; Audit independently consumes the same events into audit records. The Angular frontend implements authentication and CUSTOMER quote creation/history/detail.

## Repository Structure

```text
frontend/                    Angular + NgRx authentication and quote workflow
services/                    Gateway and five backend services
  api-gateway/
  auth-service/
  quote-service/
  pricing-service/
  notification-service/
  audit-service/
infrastructure/              Shared runtime/deployment support
  docker/
  kafka/
  postgres/
docs/                        Project planning documentation
  adr/                       Architecture Decision Records
  specs/                     Phase implementation specifications
docker-compose.yml           Shared PostgreSQL and Kafka infrastructure
```

Empty runtime directories contain `.gitkeep` files so Git retains them. `infrastructure/` is reserved for shared Docker, Kafka, and PostgreSQL support. Future service-owned Flyway migrations must live in the owning Spring Boot service, never in `infrastructure/`.

Start with [project context](docs/project-context.md), [architecture](docs/architecture.md), and the [implementation roadmap](docs/implementation-roadmap.md). Detailed planning that has not yet been supplied is explicitly marked as pending.

## Local Infrastructure

### Prerequisites

Install Docker with Docker Compose and start the Docker daemon (for example, Docker Desktop). No Java or Node installation is required for infrastructure alone. Backend skeletons require JDK 25.

### Start Infrastructure

From the repository root:

```bash
docker compose up -d
```

Defaults work without an `.env` file. Optionally copy `.env.example` to `.env` and adjust the ports or development credentials before first startup. `.env` is ignored by Git. The example credentials are public and development-only.

### Check Status

```bash
docker compose ps
docker compose ps -a
docker compose logs kafka-init
```

Wait for `postgres` and `kafka` to become healthy and `kafka-init` to exit with code `0`. The one-shot initialization job creates all six topics; `up -d` alone does not mean topic initialization has finished. No application containers run yet.

### Stop Infrastructure

```bash
docker compose down
```

Named volumes are intentionally preserved. Starting again reuses the databases and Kafka data. `docker compose down -v` explicitly deletes these volumes and their data; it is not a normal stop or restart command.

### Local Connections

| Component | Host applications | Containers on the Compose network |
| --- | --- | --- |
| PostgreSQL | `localhost:15432` | `postgres:5432` |
| Kafka bootstrap servers | `localhost:29092` | `kafka:19092` |

Host defaults are PostgreSQL `15432` and Kafka `29092`, consistently used by Compose and the services' local YAML. All applications default to the `local` profile. API Gateway does not connect to PostgreSQL/Kafka, and Auth does not use Kafka. Database-owning services retain separate logical databases. Environment variables can override these defaults; Compose reads `.env`, while Maven-started applications require exported overrides. Kafka's advertised host listener follows `KAFKA_EXTERNAL_PORT`. Container ports remain unchanged (`postgres:5432`, `kafka:19092`); published ports bind to IPv4 loopback only.

PostgreSQL defaults to username `insurance_dev` and password `local_dev_only`, overridden by `POSTGRES_USER` and `POSTGRES_PASSWORD`. The five service databases are:

| Future service | Logical database |
| --- | --- |
| Auth Service | `auth_db` |
| Quote Service | `quote_db` |
| Pricing Service | `pricing_db` |
| Notification Service | `notification_db` |
| Audit Service | `audit_db` |

Each future service must access only its own database. The shared local bootstrap account is an administrative development convenience, not enforcement of service isolation. Phase 3 Flyway migrations now create each service's tables and educational seeds. See the schema inventory below.

Kafka main topics are `insurance.user.events`, `insurance.quote.events`, and `insurance.pricing.events`. Each has a matching `.dlq` topic. All six use **3 partitions and replication factor 1**. DLQ processing, event contracts, and application producers/consumers come later.

See [infrastructure guidance](infrastructure/README.md) for initialization behavior, verification commands, persistence checks, and troubleshooting.

Validation results and the machine-specific port overrides are recorded in [Phase 1 validation](infrastructure/phase-1-validation.md).

## Backend Services

| Service | Port | Database | Current Status |
| --- | ---: | --- | --- |
| API Gateway | 8080 | None | Auth/admin Pricing/Quote routing and JWT authorization |
| Auth Service | 8081 | auth_db | Registration and login |
| Quote Service | 8082 | quote_db | Creation, snapshots, CUSTOMER ownership and ADMIN retrieval |
| Pricing Service | 8083 | pricing_db | Internal calculation and ADMIN rule management |
| Notification Service | 8084 | notification_db | QuotePriced consumer |
| Audit Service | 8085 | audit_db | QuotePriced audit consumer |

Use JDK 25. The root parent/aggregator pins Spring Boot 4.1.1 and Spring Cloud 2025.1.3 (Gateway 5.0.3). Maven Wrapper 3.3.4 supplies Maven 3.9.16; no separately installed Maven is required.

```bash
./mvnw clean verify
docker compose up -d
./mvnw -pl services/auth-service spring-boot:run
```

Auth defaults to the local profile, PostgreSQL port 15432, and a public development-only signing key. No launcher or environment setup is needed for the current local database. Use the module-specific Maven commands in the backend guide for other services. All six expose `/actuator/health`; only health/info are exposed through Actuator. Ports and infrastructure connections support environment overrides. Auth now exposes registration/login backed by its User entity. Gateway now routes Auth requests; Pricing exposes its internal calculation endpoint and protected admin rule APIs. Quote exposes CUSTOMER-only creation and secure CUSTOMER/ADMIN retrieval, and persists QuotePriced intent atomically through its outbox; Notification and Audit consume events idempotently. Database migrations and seeds were implemented in Phase 3.

See [backend startup and dependency guidance](services/README.md) for per-service packages, environment variables, temporary security behavior, test boundaries, and Maven commands.

Phase 2 startup, health, database, and Kafka safety evidence is recorded in [validation results](docs/phase-2-validation.md).

## Phase 3 — Database Foundation and Flyway

Flyway owns all service schemas. Migrations live under `services/<service>/src/main/resources/db/migration/`; Hibernate schema generation remains disabled.

| Database | Tables (excluding Flyway history) |
| --- | --- |
| `auth_db` | `users`, `refresh_tokens`, `outbox_events` |
| `quote_db` | `vehicle_brands`, `vehicle_models`, `quotes`, `quote_drivers`, `quote_vehicles`, `pricing_breakdowns`, `pricing_adjustments`, `outbox_events` |
| `pricing_db` | `pricing_rules`, `outbox_events` |
| `notification_db` | `notifications`, `processed_events` |
| `audit_db` | `audit_records`, `processed_events` |

Starting the five database services with the local profile applies 15 migrations, including four brands, twelve models, and seven learning-only pricing rules. Normal restarts validate existing migrations without duplicating seeds. No user accounts are seeded. Outbox and processed-event tables do not implement messaging behavior.

See [database design](docs/database-design.md), [validation results](docs/phase-3-validation.md), and [intentional local database rebuild instructions](services/README.md#intentional-local-database-rebuild). No database reset is needed for ordinary development. Phase 4 authentication is documented below; Phase 5 adds Gateway Auth routing and JWT validation. Phase 6 adds internal database-driven premium calculation. Phase 7 adds ADMIN-only pricing-rule administration. Phase 8 adds CUSTOMER quote creation and stored pricing snapshots. Phase 9 adds secure quote retrieval and pagination. Phase 10 adds Angular registration/login and a protected dashboard foundation.

## Phase 4 — Authentication Basics

Auth Service now provides `POST /api/v1/auth/register` (201) and `POST /api/v1/auth/login` (200). Registration canonicalizes email, hashes passwords with BCrypt cost 12, and always creates an ACTIVE CUSTOMER. Client-supplied role/status fields are rejected. Login rejects unknown accounts, incorrect passwords, and disabled accounts with the same 401 error.

Login issues an HS256 JWT access token with a 15-minute default lifetime, configurable through `AUTH_JWT_ACCESS_TOKEN_TTL`. The local profile includes a public development-only signing key. `AUTH_JWT_SECRET` can override it and must be supplied outside the local profile; never reuse the local key outside this learning project. No refresh tokens are implemented.

See [Auth Service documentation](services/auth-service/README.md) for request/response details, required environment values, and explicit local ADMIN creation through registration followed by a manual database promotion. See [Phase 4 validation](docs/phase-4-validation.md) for test and live-database evidence. Phase 5 is documented below.

## Phase 5 — API Gateway and JWT Validation

Run Auth as above, then in a second terminal run:

```bash
./mvnw -pl services/api-gateway spring-boot:run
```

Normal API calls now use `POST http://localhost:8080/api/v1/auth/register` and `POST http://localhost:8080/api/v1/auth/login`. Gateway forwards `/api/v1/auth/**` to `AUTH_SERVICE_URL` (default `http://localhost:8081`). Only those two POST endpoints and GET health/info are public; other requests require a valid HS256 Bearer token with the expected issuer. Missing/invalid/expired tokens receive safe JSON 401 responses. Phase 7 adds protected ADMIN pricing-rule routes below.

Auth and Gateway share the public local development key; outside local supply the same `AUTH_JWT_SECRET` securely. CORS allows configurable `GATEWAY_CORS_ALLOWED_ORIGINS` (default `http://localhost:4200`) without credentials. Gateway forwards and returns a bounded `X-Correlation-ID`, generating one when needed.

See [Gateway commands, curl examples, configuration, and limitations](services/api-gateway/README.md) and [Phase 5 validation](docs/phase-5-validation.md). Phase 6 is documented below.

## Phase 6 — Pricing Service MVP

Run Pricing independently with the existing local database on port 15432:

```bash
./mvnw -pl services/pricing-service spring-boot:run
```

`POST http://localhost:8083/internal/v1/pricing/calculate` returns an educational THB premium and detailed rule breakdown. It loads enabled/effective rules from `pricing_db`, derives ages using UTC, selects the newest matching rule per category (UUID tie-break), and applies factors in a fixed order with BigDecimal HALF_UP rounding at each step.

This endpoint is internal and temporarily unauthenticated; it is **not routed through Gateway**. Service-to-service authentication is deferred to Phase 25. Calculations do not persist results or write outbox/Kafka events. Existing schemas and seeds are unchanged. Pricing administration is added in Phase 7 below; Quote integration is added in Phase 8 below.

See [Pricing request/response examples and rule semantics](services/pricing-service/README.md), [Phase 6 specification](docs/specs/phase-6-pricing-service-mvp.md), and [validation results](docs/phase-6-validation.md). Phase 7 is documented below.

## Phase 7 — Pricing Administration

ADMIN users can list/get/create/update/enable/disable pricing rules through `http://localhost:8080/api/v1/admin/pricing/rules`. Gateway forwards to configurable `PRICING_SERVICE_URL` (default localhost:8083). Both Gateway and Pricing validate JWTs and enforce ADMIN; CUSTOMER receives 403 and missing/invalid authentication receives 401. Auth, Gateway, and Pricing share the same local key/issuer; non-local environments require configured secrets.

List supports bounded pagination and optional ruleType/enabled filters. PUT and PATCH require the last returned version; stale or concurrent updates return 409. Future-effective rules are allowed, and existing calculation selection/rounding remain unchanged. No deletes, migrations, seed changes, Kafka/outbox events, or UI were added. Internal calculation stays off Gateway; service-to-service authentication remains deferred.

See [Pricing administration guide](services/pricing-service/README.md), [Gateway guide](services/api-gateway/README.md), [Phase 7 specification](docs/specs/phase-7-pricing-administration.md), and [validation results](docs/phase-7-validation.md). Phase 8 is documented below.

## Phase 8 — Quote Service MVP

Start Quote in its own terminal with `./mvnw -pl services/quote-service spring-boot:run`. It defaults to local and PostgreSQL port 15432. With Auth, Pricing, and Gateway also running, CUSTOMER tokens can call `POST http://localhost:8080/api/v1/quotes`. Quote independently validates JWTs and derives customer_id only from the UUID subject. ADMIN-only tokens cannot create quotes.

Quote validates input and active brand/model references, calls Pricing directly over synchronous REST, and atomically stores a PRICED quote with driver, vehicle-name, and pricing snapshots. Expiration is 30 days after creation. Pricing failure returns a safe 502/503 without a partial quote. Quote → Pricing currently has no service credentials; service authentication remains deferred to Phase 25.

Existing schemas and seeds are unchanged. Phase 8 added no retrieval/history, editing, repricing, expiration job, Kafka, or outbox writes. Phase 9 retrieval is described below. See [Quote startup, curl, response, and code guide](services/quote-service/README.md), [specification](docs/specs/phase-8-quote-service-mvp.md), and [validation](docs/phase-8-validation.md). Phase 9 adds secure quote retrieval and pagination. Phase 10 adds Angular registration/login and a protected dashboard foundation.

## Phase 9 — Quote Retrieval and Ownership

`GET /api/v1/quotes/{id}` returns a stored snapshot. CUSTOMER can read only their own quotes; cross-customer and missing IDs return identical 404s. ADMIN can read any quote. `GET /api/v1/quotes?page=0&size=20` lists the customer's own quotes or all quotes for ADMIN, with maximum size 100 and fixed createdAt DESC / ID DESC order. Client customerId/status/sort filters are rejected. Quote validates JWTs and enforces ownership independently of Gateway.

Retrieval uses read-only database queries, with no Pricing call, current catalog lookup, recalculation, status transition, or messaging. A two-step ID-page/snapshot fetch preserves pagination without N+1 queries. All 267 normal tests and seven Quote PostgreSQL integration tests pass; live Gateway ownership checks and temporary-data cleanup passed. Newly built Gateway creation was also validated on temporary ports.

See [Quote API guide](services/quote-service/README.md#phase-9--retrieval-and-ownership), [specification](docs/specs/phase-9-quote-retrieval-and-ownership.md), and [validation](docs/phase-9-validation.md). Phase 10 is documented below.

## Phase 10 — Angular Foundation

Angular 22.2.2 and NgRx 22.0.1 provide registration, login, protected dashboard, role-aware navigation, and local logout. Reactive Forms own fields/validation, Signals own local loading/errors/password visibility, and NgRx owns the shared memory-only session. Angular calls Gateway only. Browser refresh intentionally logs out; refresh tokens and quote UI are deferred.

With infrastructure, Auth (8081), and Gateway (8080) running, start the frontend:

```bash
cd frontend
npm ci
npm start
```

Open http://localhost:4200. Configure the public Gateway URL in `frontend/src/environments/environment.ts`. Run `npm test` and `npm run build` from `frontend/`. See the [frontend setup and code guide](frontend/README.md), [specification](docs/specs/phase-10-angular-foundation.md), and [validation](docs/phase-10-validation.md). Phase 11 is documented below.

## Phase 11 — Angular Quote Flow

CUSTOMER navigation now includes New Quote and My Quotes. `/quotes/new` uses database-backed brand/model selection and validated Reactive Forms. Creation through Gateway opens `/quotes/:id`, which displays the stored driver/vehicle/pricing snapshot. `/quotes` lists owned quotes with basic backend pagination. No frontend premium calculation or customerId override exists.

Quote Service adds read-only, authenticated GET `/api/v1/vehicles/brands` and `/api/v1/vehicles/brands/{brandId}/models`; Gateway routes them to Quote. Only active rows are exposed as id/name DTOs. No migrations, Auth/Pricing behavior changes, outbox writes or Kafka events were added. Restart Gateway and Quote to load these additions; Auth and Pricing must also run for creation.

See the [frontend guide](frontend/README.md#phase-11--customer-quote-workflow), [specification](docs/specs/phase-11-angular-quote-flow.md), and [validation](docs/phase-11-validation.md). Phase 12 is documented below.

## Phase 12 — Basic QuotePriced Kafka Event

After quote persistence commits, Quote Service sends a version 1 JSON QuotePriced envelope to `insurance.quote.events`, keyed by quote ID. Safe request correlation is preserved. Kafka failure is logged without rolling back the quote or changing its successful 201 response. No consumers, custom retry/DLQ behavior or outbox writes are implemented. This direct database/Kafka dual-write gap will be addressed in Phase 16.

Quote defaults to Kafka on port 29092; run `./mvnw -pl services/quote-service spring-boot:run` without a port override. See [Quote startup/configuration](services/quote-service/README.md#phase-12--quotepriced-producer), [event contract](docs/quote-priced-event.md), [specification](docs/specs/phase-12-quote-priced-event.md), and [validation](docs/phase-12-validation.md). Phase 13 is documented below.

## Phase 13 — Notification Consumer

Notification now consumes QuotePriced v1 from `insurance.quote.events` using its own `notification-service` group and writes a simulated notification after validation. Kafka offsets advance only after the local DB transaction commits. The group defaults to earliest for historical learning events. Invalid events or persistence failures stop consumption; unknown types are intentionally skipped. Existing event ID uniqueness does not provide full idempotency. No processed_events, retries/DLQ, external messaging provider, Audit consumer or Outbox logic is added.

See [Notification startup and learning guide](services/notification-service/README.md), [specification](docs/specs/phase-13-notification-consumer.md) and [validation](docs/phase-13-validation.md). Phase 14 is documented below.

## Phase 14 — Audit Consumer

Audit independently consumes QuotePriced v1 from insurance.quote.events in its own audit-service group and stores compact audit records. Notification behavior is unchanged; the two groups independently track offsets. Audit uses earliest for historical records, RECORD acknowledgment after DB commit, and stop-on-failure behavior. A minimal Audit V3 migration widens correlation_id to match the existing 128-character event contract. No idempotency, retry/DLQ, Outbox or Audit REST/UI is implemented.

Run `./mvnw -pl services/audit-service spring-boot:run` with the same local infrastructure. See [Audit guide](services/audit-service/README.md), [specification](docs/specs/phase-14-audit-consumer.md) and [validation](docs/phase-14-validation.md). Phase 15 is documented below.

## Phase 15 — Idempotent Consumers

Notification and Audit now atomically register eventId plus their stable consumer name in their own processed_events table before business persistence. A duplicate is successful processing with no second business row; original timestamps remain unchanged. PostgreSQL uniqueness handles concurrent duplicates, and legacy rows gain markers lazily when replayed. No schema, producer contract, group/topic or startup-command changes.

See [Notification guide](services/notification-service/README.md#phase-15--idempotent-processing), [Audit guide](services/audit-service/README.md#phase-15--idempotent-processing), [specification](docs/specs/phase-15-idempotent-consumers.md) and [validation](docs/phase-15-validation.md). Phase 16 is documented below.

## Phase 16 — Transactional Outbox

Quote Service atomically commits the quote graph and one PENDING QuotePriced envelope. A scheduled publisher sends stored JSON to the existing topic, using quote ID as key, and marks PUBLISHED only after Kafka acknowledgment. HTTP 201 means quote plus event intent are committed, even when Kafka is temporarily unavailable. Event IDs stay unchanged on later attempts; Phase 15 consumers handle duplicate delivery.

Local startup commands are unchanged. Polling is enabled locally, defaults to 2000 ms / 50 rows, and can be disabled with `OUTBOX_PUBLISHER_ENABLED=false`. A PostgreSQL session advisory lock excludes competing publishers without holding an open transaction during Kafka I/O. Quote V6 widens only the outbox correlation column to preserve the existing 128-character contract.

See [Quote guide](services/quote-service/README.md), [outbox design](docs/transactional-outbox.md), [specification](docs/specs/phase-16-transactional-outbox.md), and [validation](docs/phase-16-validation.md). Phase 17 is documented below. Earlier phase sections record historical milestones; the Phase 12 direct producer has been removed.

## Phase 17 — Retry and DLQ

Notification and Audit now retry processing failures with three total attempts and fixed 1000 ms backoff, configurable through `KAFKA_CONSUMER_MAX_ATTEMPTS` / `KAFKA_CONSUMER_BACKOFF_MS`. Invalid supported messages go directly to the existing `insurance.quote.events.dlq`; unknown event types still skip, and processed duplicates still succeed.

The original key/value bytes are preserved with safe source-coordinate and consumer-identity headers. Source progress advances only after business success or acknowledged DLQ publication. If the DLQ send fails, the source remains replayable. Both groups fail independently; Quote's outbox and consumer idempotency are unchanged. No schema, topic, partition-count or business API changes.

See [retry/DLQ policy](docs/retry-and-dlq.md), [specification](docs/specs/phase-17-retry-and-dlq.md), and [validation](docs/phase-17-validation.md). Phase 18 — Quote Expiration is implemented; see the [specification](docs/specs/phase-18-quote-expiration.md) and [validation](docs/phase-18-validation.md). No DLQ replay mechanism is implemented.

## Phase 22 — Audit UI

ADMIN users can browse `/admin/audit`, apply exact filters and UTC occurrence ranges, paginate, and inspect safe read-only JSON details. Gateway and Audit Service independently require ADMIN JWTs. No audit mutations or schema changes. See [API/UI specification](docs/specs/phase-22-audit-ui.md) and [validation/file manifest](docs/phase-22-validation.md). Phase 23 — Refresh Tokens is not started.
