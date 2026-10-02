# Database Design

Status: Approved database boundaries recorded; detailed design pending migration from approved planning notes.

All backend services will use PostgreSQL. Each microservice will own its own logical database. Cross-service database access is prohibited.

`infrastructure/postgres/` is reserved for shared PostgreSQL setup and initialization. Future service-owned Flyway migrations must live in the owning Spring Boot service, not under `infrastructure/`.

Database schemas, tables, connections, and migrations are not defined in Phase 0. Transactional Outbox will be added later; its schema is not specified here.
