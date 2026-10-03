# Phase 2 Validation

Status: Complete, validated on 2026-10-02. Phase 3 has not started.

## Versions and Build Structure

| Component | Selected version |
| --- | --- |
| Java | 25 (installed Oracle JDK 25.0.1) |
| Spring Boot | 4.1.1, common root parent |
| Maven | 3.9.16 |
| Maven Wrapper | 3.3.4, `only-script`, Maven Central ZIP distribution |
| Spring Cloud | 2025.1.3 BOM |
| Spring Cloud Gateway | 5.0.3, `spring-cloud-starter-gateway-server-webflux` |

The root POM is both parent and aggregator. Each of the six service directories has its own POM and executable Boot JAR. The root wrapper is shared across all modules. There is no shared Java library. Dependency/plugin versions come from the Boot parent and Cloud BOM. Official compatibility references are linked in [services/README.md](../services/README.md).

## Executed Build and Startup Checks

- `./mvnw --version`: Maven 3.9.16 and Java 25.0.1 confirmed.
- `./mvnw clean verify`: all six modules passed; six HTTP/context tests, zero failures or errors.
- `./mvnw -B -pl services/auth-service -am clean verify`: parent and Auth module passed independently.
- `docker compose up -d`: existing Phase 1 infrastructure healthy, topic initializer completed successfully. No Compose changes were needed.
- Started all six packaged JARs simultaneously with `java -Xmx256m -jar services/<module>/target/<module>-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`. Exported existing local environment overrides were used. Temporary `--management.endpoint.health.show-details=always` was used only in the validation processes to assert database health; it is not configured in the repository.
- HTTP GET `/actuator/health`: 200 / UP for all six applications at the ports below. HTTP GET `/actuator/info`: 200. `/actuator/env`: unavailable (403 for servlet services, 404 for Gateway).
- `./mvnw -B -pl services/auth-service spring-boot:run -Dspring-boot.run.profiles=local` with `AUTH_SERVICE_PORT=18081`: passed; environment port override honored, database connection successful. Default health response reported UP with liveness/readiness group names and no component details. An initial validation assertion expected only a status field; it was corrected to allow standard health-group metadata. No application fix was needed.

| Service | Port | Health | Database result |
| --- | ---: | --- | --- |
| API Gateway | 8080 | UP | No datasource or database dependencies |
| Auth Service | 8081 | UP | `auth_db`, PostgreSQL health UP |
| Quote Service | 8082 | UP | `quote_db`, PostgreSQL health UP |
| Pricing Service | 8083 | UP | `pricing_db`, PostgreSQL health UP |
| Notification Service | 8084 | UP | `notification_db`, PostgreSQL health UP |
| Audit Service | 8085 | UP | `audit_db`, PostgreSQL health UP |

Each startup log confirmed its service-specific JDBC URL. A `pg_stat_activity` query showed active client connections in each of the five service databases. No default generated security password appeared in application logs.

## Database Safety

Executed against each service database using the PostgreSQL container's `psql`:

```sql
SELECT tablename FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY tablename;

SELECT count(*) FROM flyway_schema_history;
```

Only `flyway_schema_history` exists in each database, with zero rows. Flyway created this metadata automatically with zero migrations; its no-migrations warning is expected in Phase 2. No entities, repositories, business tables, or migration files were added. Hibernate DDL and SQL initialization remain disabled.

## Kafka Safety

Captured before and after application startup:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe --topic 'insurance.*'
docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:19092 --topic 'insurance.*'
```

The full topic list was unchanged. All six domain/DLQ topic IDs, partition counts (3), replication factors (1), leaders, and replica assignments remained unchanged. Every domain-topic partition offset was unchanged, including the pre-existing Phase 1 CLI smoke-test record. Applications published no records and created no topics. Source inspection confirmed no listeners, producer calls, topic declarations, or gateway routes.

## Repository and Scope Checks

- Six service POMs and six application classes exist, with the common Java/Boot baseline.
- Startup tests require neither local PostgreSQL nor Docker: the test profile explicitly excludes persistence auto-configuration. They are context/HTTP tests, not database integration tests.
- Testcontainers dependencies are available, but no integration tests or containers are started by the build.
- No H2, business controllers, shared business library, migration scripts, Angular code, or infrastructure changes were added.
- No Gradle files, wrapper, configuration, or README commands exist; the unused Phase 0 `.gradle/` ignore entry was removed.
- `.env` stays ignored. `.env.example` contains public development defaults only. No real secrets were added.
- `git diff --check` passed. No commit was made for Phase 2 implementation.

## Boundaries and Handoff

All validation application processes were stopped; existing PostgreSQL and Kafka containers remain healthy and running. Developers start services explicitly with the documented Maven commands.

Local configuration assumes host-running applications and the existing shared development database account. That account does not enforce service isolation. Kafka properties prepare future connections; no application Kafka messaging or broker-health integration is claimed. Temporary servlet security exposes only health/info and denies other requests; it is not production authentication.

Phase 2 is ready for Phase 3 — Database Foundation and Flyway. Business schemas/migrations, authentication, entities/repositories, APIs, gateway routes, Angular, Kafka producers/consumers, retry/DLQ handling, and Transactional Outbox remain deferred to their approved later phases.
