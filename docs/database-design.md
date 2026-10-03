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
