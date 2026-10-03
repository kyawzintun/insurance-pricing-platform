# Backend Service Skeletons

Phase 2 supplied six independently runnable applications. Phase 3 adds service-owned Flyway migrations and educational seeds; no business APIs, entities, Kafka message handlers, or gateway routes exist.

## Build Baseline

- Java **25**, matching the installed JDK (validated with 25.0.1).
- Spring Boot **4.1.1** in the root parent POM.
- Maven **3.9.16**, downloaded by Maven Wrapper **3.3.4**, `only-script` distribution. The root `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` serve all modules; no global Maven installation is required.
- Spring Cloud **2025.1.3**, importing its dependency BOM centrally; Gateway **5.0.3**, artifact `org.springframework.cloud:spring-cloud-starter-gateway-server-webflux`.

Spring's [Java requirements](https://docs.spring.io/spring-boot/system-requirements.html) support Java 25. Its [Cloud compatibility table](https://spring.io/projects/spring-cloud/) maps 2025.1.x to Boot 4.1.x starting at 2025.1.2. Gateway 5.0.3 comes from the [2025.1.3 BOM](https://repo.maven.apache.org/maven2/org/springframework/cloud/spring-cloud-dependencies/2025.1.3/spring-cloud-dependencies-2025.1.3.pom); the compatibility verifier remains enabled.

| Module | Base package | Default port | Database |
| --- | --- | ---: | --- |
| `api-gateway` | `com.insurance.platform.gateway` | 8080 | None |
| `auth-service` | `com.insurance.platform.auth` | 8081 | `auth_db` |
| `quote-service` | `com.insurance.platform.quote` | 8082 | `quote_db` |
| `pricing-service` | `com.insurance.platform.pricing` | 8083 | `pricing_db` |
| `notification-service` | `com.insurance.platform.notification` | 8084 | `notification_db` |
| `audit-service` | `com.insurance.platform.audit` | 8085 | `audit_db` |

The root POM aggregates modules and manages versions. It contains no shared Java code. Each module has its own application class, configuration, POM, and startup/health test.

## Commands

From the repository root with JDK 25 selected (`JAVA_HOME` if necessary):

```bash
./mvnw --version
./mvnw clean verify
./mvnw -pl services/auth-service -am clean verify
```

Windows uses `mvnw.cmd` for the same goals. The first build needs internet access to download Maven and dependencies.

Start infrastructure and export your trusted local environment file before launching an application. Docker Compose loads `.env` itself; Maven/Spring Boot do not automatically read it.

```bash
docker compose up -d
# POSIX shell; execute in each terminal used to run a service.
set -a
[ ! -f .env ] || . ./.env
set +a
./mvnw -pl services/auth-service spring-boot:run -Dspring-boot.run.profiles=local
```

Replace `services/auth-service` with any module from the table. Open separate terminals for simultaneous startup. Do not add `-am` to the run command: it would attempt to run the aggregator as an application. Stop each application with Ctrl+C; stop infrastructure separately with `docker compose down`.

The `local` profile is explicit. It binds to `127.0.0.1` by default, uses the module's port, and provides development database credentials. Non-local deployments must supply their own datasource/configuration. No production profile is defined.

## Environment Overrides

- Database: `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`. Defaults are `localhost`, `5432`, `insurance_dev`, `local_dev_only`. The database name stays service-specific.
- Kafka: `KAFKA_BOOTSTRAP_SERVERS` takes precedence. Otherwise the local profile uses `localhost:${KAFKA_EXTERNAL_PORT}`, defaulting to `localhost:9092`. This preserves Phase 1 port overrides without duplicating them.
- Ports: `API_GATEWAY_PORT`, `AUTH_SERVICE_PORT`, `QUOTE_SERVICE_PORT`, `PRICING_SERVICE_PORT`, `NOTIFICATION_SERVICE_PORT`, `AUDIT_SERVICE_PORT`. Standard Spring `SERVER_PORT` also overrides the port for one process.
- Bind address: `SERVER_ADDRESS`, default `127.0.0.1`. Future container deployment would need a suitable bind address plus internal database/Kafka addresses; no service containers are added in Phase 2.

No local override values or real secrets are stored in application configuration. `.env.example` contains only public development values.

## Dependencies and Temporary Behavior

| Services | Dependencies |
| --- | --- |
| All | Actuator; Spring Boot test starter |
| Gateway | Spring Cloud Gateway Server WebFlux (reactive stack) |
| All five database services | Web MVC, Security, Data JPA, Flyway starter and PostgreSQL Flyway module, PostgreSQL JDBC driver; test-scoped Boot Testcontainers support, Testcontainers JUnit Jupiter and PostgreSQL |
| Auth, Quote, Pricing | Validation |
| Quote, Pricing, Notification, Audit | Spring Boot Kafka starter (Spring Kafka integration) |

Notification includes Web MVC and the same small Security baseline for consistent HTTP health behavior. Only `health` and `info` are exposed. All database services use a temporary `Phase2SecurityConfiguration`: health/info are public, all other requests are denied, and form login/HTTP Basic are disabled. Default user auto-configuration is excluded to avoid a generated password. There is no authentication implementation. Replace this baseline when approved security features are introduced. Gateway exposes health/info and has no application routes or JWT configuration.

Hibernate uses `ddl-auto: none`, SQL initialization is disabled, and no entities/repositories are present. Flyway now owns the Phase 3 tables and seeds, applying service-owned migrations on startup. Its history records each applied migration; normal restarts validate checksums and do not repeat seeds.

Kafka configuration contains only bootstrap addresses. No listeners, producers, topic beans, retry settings, or DLQ processing are implemented. Broker connectivity is not exercised by application messaging in Phase 2.

## Tests and Health

Each module's `@SpringBootTest` starts a real HTTP server on a random port and checks `/actuator/health`. The database-service test profile excludes datasource, Hibernate, and Flyway auto-configuration; it intentionally tests context/HTTP startup rather than database integration. No H2, running local PostgreSQL, or Docker is required for `clean verify`. Testcontainers dependencies are prepared but no containers are started by tests.

With the six local applications running on their default ports:

```bash
for port in 8080 8081 8082 8083 8084 8085; do
  curl --fail "http://localhost:$port/actuator/health"
  printf '\n'
done
```

Expect HTTP 200 with `"status":"UP"`; standard liveness/readiness group names may also appear. Database-backed services include the datasource health check, while response details remain hidden by default. Startup logs and separate PostgreSQL checks provide additional database-connectivity evidence during validation.

See [Phase 2 validation](../docs/phase-2-validation.md) for the executed commands, results, and remaining boundaries.

## Phase 3 — Database Foundation

Migration files live in `services/<service>/src/main/resources/db/migration/`. There are 15 versioned migrations across the five database services; Gateway remains database-free. See [database design](../docs/database-design.md) for the complete table, constraint, index, and seed inventory.

Start infrastructure, export `.env`, and run each database service with the `local` profile using the commands above. Flyway runs before JPA initializes. A healthy application indicates startup completed; inspect logs for the applied migration versions. The normal Maven context tests deliberately exclude persistence, so `clean verify` alone does not validate SQL migrations against PostgreSQL.

### Intentional Local Database Rebuild

Normal restarts preserve data and never require a reset. For a deliberate clean rebuild of a disposable local database, first stop all applications connected to that database and back up any data you need. The commands below destroy **only `quote_db`**, including its migration history; they are an example to run only when that data loss is intended. They do not remove Kafka data or Docker volumes.

Optional backup (replace the output path with a location outside the repository):

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -Fc quote_db' > /path/outside/repository/quote_db-before-reset.dump
```

After confirming that the backup succeeded or that the data is disposable:

```bash
docker compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" quote_db && createdb -U "$POSTGRES_USER" quote_db'
# With .env exported as described above:
./mvnw -pl services/quote-service spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway recreates Quote's schema and seeds from its migrations. Do not recreate tables manually or delete only the history table. If connections remain, `dropdb` fails rather than forcibly disconnecting other clients. Replace the database and matching service only when intentionally rebuilding another service's database. No reset was required during Phase 3 validation.
