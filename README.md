# Insurance Pricing Platform

A learning project for practicing Angular + NgRx, Spring Boot 4, Spring Security, Kafka, PostgreSQL, Docker, microservices, and specification-first AI-assisted SDLC.

The project focuses on a simple car-insurance pricing platform. It is not intended to implement realistic actuarial insurance pricing. The main learning targets are Spring Boot/microservices, Kafka, Angular + NgRx, PostgreSQL, and specification-first AI-assisted development.

## Current Project Status

**Phase 7 — Pricing Administration: Complete**

Phase 0 is complete. Phase 1 shared PostgreSQL and Kafka infrastructure has passed startup, connectivity, initialization, and persistence validation. Phase 2 added six Maven-based service skeletons. Phase 3 adds service-owned Flyway schemas and educational seed data. Phase 4 implements registration and login in Auth Service only. Phase 5 adds Gateway Auth routing and JWT validation. Phase 6 adds internal database-driven premium calculation. Phase 7 adds ADMIN-only pricing-rule administration. Phase 8 has not started. Implementation will proceed phase by phase.

## Main Users

- **CUSTOMER**
- **ADMIN**

## Planned Features

Customer registration/login, Gateway JWT validation, and internal database-driven pricing, and ADMIN pricing-rule management are implemented (Phases 4–7). The following features remain planned:

- Car insurance quote creation
- Quote pricing breakdown
- Quote ownership security
- Kafka-based notifications
- Kafka-based audit logging
- 30-day quote expiration

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

REST will be used for synchronous communication when an immediate response is needed. Each microservice will own its own logical database, with no cross-service database access. Transactional Outbox will be introduced in a later phase. PostgreSQL and Kafka are configured for local development. Auth Service implements registration and login. The gateway and other backend services remain skeletons; the frontend and other business features remain planned.

## Repository Structure

```text
frontend/                    Planned Angular + NgRx application
services/                    Gateway and five backend service skeletons
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
  specs/                     Future implementation specifications
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
| PostgreSQL | `localhost:5432` | `postgres:5432` |
| Kafka bootstrap servers | `localhost:9092` | `kafka:19092` |

Host ports can be changed with `POSTGRES_PORT` and `KAFKA_EXTERNAL_PORT`. On the machine used for Phase 1 validation, an ignored `.env` sets PostgreSQL to `localhost:15432` and Kafka to `localhost:29092` because another project occupies the defaults. This local file is not included in a fresh clone. Kafka's advertised host listener follows `KAFKA_EXTERNAL_PORT`. Published ports bind to IPv4 loopback only; container clients use the internal addresses on the Compose network.

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
| API Gateway | 8080 | None | Auth/admin Pricing routing and JWT authorization |
| Auth Service | 8081 | auth_db | Registration and login |
| Quote Service | 8082 | quote_db | Skeleton |
| Pricing Service | 8083 | pricing_db | Internal calculation and ADMIN rule management |
| Notification Service | 8084 | notification_db | Skeleton |
| Audit Service | 8085 | audit_db | Skeleton |

Use JDK 25. The root parent/aggregator pins Spring Boot 4.1.1 and Spring Cloud 2025.1.3 (Gateway 5.0.3). Maven Wrapper 3.3.4 supplies Maven 3.9.16; no separately installed Maven is required.

```bash
./mvnw clean verify
docker compose up -d
./mvnw -pl services/auth-service spring-boot:run
```

Auth defaults to the local profile, PostgreSQL port 15432, and a public development-only signing key. No launcher or environment setup is needed for the current local database. Use the module-specific Maven commands in the backend guide for other services. All six expose `/actuator/health`; only health/info are exposed through Actuator. Ports and infrastructure connections support environment overrides. Auth now exposes registration/login backed by its User entity. Gateway now routes Auth requests; Pricing exposes its internal calculation endpoint and protected admin rule APIs. Other business APIs and application Kafka messaging remain unimplemented. Database migrations and seeds were implemented in Phase 3.

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

See [database design](docs/database-design.md), [validation results](docs/phase-3-validation.md), and [intentional local database rebuild instructions](services/README.md#intentional-local-database-rebuild). No database reset is needed for ordinary development. Phase 4 authentication is documented below; Phase 5 adds Gateway Auth routing and JWT validation. Phase 6 adds internal database-driven premium calculation. Phase 7 adds ADMIN-only pricing-rule administration. Phase 8 has not started.

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

This endpoint is internal and temporarily unauthenticated; it is **not routed through Gateway**. Service-to-service authentication is deferred to Phase 25. Calculations do not persist results or write outbox/Kafka events. Existing schemas and seeds are unchanged. Pricing administration is added in Phase 7 below; Quote integration remains deferred.

See [Pricing request/response examples and rule semantics](services/pricing-service/README.md), [Phase 6 specification](docs/specs/phase-6-pricing-service-mvp.md), and [validation results](docs/phase-6-validation.md). Phase 7 is documented below.

## Phase 7 — Pricing Administration

ADMIN users can list/get/create/update/enable/disable pricing rules through `http://localhost:8080/api/v1/admin/pricing/rules`. Gateway forwards to configurable `PRICING_SERVICE_URL` (default localhost:8083). Both Gateway and Pricing validate JWTs and enforce ADMIN; CUSTOMER receives 403 and missing/invalid authentication receives 401. Auth, Gateway, and Pricing share the same local key/issuer; non-local environments require configured secrets.

List supports bounded pagination and optional ruleType/enabled filters. PUT and PATCH require the last returned version; stale or concurrent updates return 409. Future-effective rules are allowed, and existing calculation selection/rounding remain unchanged. No deletes, migrations, seed changes, Kafka/outbox events, or UI were added. Internal calculation stays off Gateway; service-to-service authentication remains deferred.

See [Pricing administration guide](services/pricing-service/README.md), [Gateway guide](services/api-gateway/README.md), [Phase 7 specification](docs/specs/phase-7-pricing-administration.md), and [validation results](docs/phase-7-validation.md). Phase 8 — Quote Service MVP has not started.
