# Testing and Reproducible Evidence

All Maven commands run at the repository root using JDK 25. `verify` builds/repackages all services. Normal tests include real local HTTP/security stacks with controlled collaborators; PostgreSQL/Kafka services are not required. Integration profiles add Maven Failsafe tests using disposable Testcontainers resources and real migrations.

```bash
./mvnw verify
./mvnw -Ppostgres-it verify
./mvnw -Pkafka-it verify
```

Docker must be available to the calling process for either integration profile. Run profiles separately. To run integration tests without repeating Surefire unit tests:

```bash
./mvnw -B -Ppostgres-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false verify
./mvnw -B -Pkafka-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false verify
```

Failsafe still runs its configured `*PostgresIT` or `*KafkaIT` classes. Do not use `-DskipTests` for this purpose; it can skip the validation you intended to run. Surefire/Failsafe XML reports under each service's ignored `target/` are the authoritative count for a run; historical counts describe their recorded revision.

## Boundaries Covered

| Layer | Checks |
| --- | --- |
| Unit / HTTP | Calculation rules, DTO validation, JWT algorithms/roles, ownership, cookie/CORS behavior, safe errors, circuit/Operations responses |
| PostgreSQL | Actual Flyway, commit/rollback, optimistic and row locking, concurrent transitions/rotation, processed markers, snapshots and bounded SELECT counts |
| Kafka | Real broker, outbox delivery/crash windows, duplicate delivery, independent groups, invalid bytes, bounded retry and DLQ recovery failure |
| Frontend | SignalStore state/cancellation/reset, guards, interceptor origin restrictions, single-flight refresh, components, error/empty states |
| Disposable E2E | Packaged service processes, PostgreSQL/Kafka, real HTTP and asynchronous side effects |
| Performance | Result equivalence plus query-plan/buffer evidence on deterministic disposable data |

## Frontend

```bash
cd frontend
npm ci
npm test
npm run build
```

If an existing global npm cache has permission problems, `npm ci --cache /tmp/insurance-portfolio-npm-cache` uses a separate disposable cache without changing global ownership.

`npm test` is non-watch Angular/Vitest with jsdom. A production build checks lazy bundles and configured budgets. There is no configured lint script or backend formatter. Prettier is an existing frontend development dependency; avoid unrelated repository-wide reformatting.

## PostgreSQL Performance

```bash
python3 infrastructure/validation/phase27-postgres-performance.py --output /tmp/portfolio-performance.json
```

Requires Docker and Python 3 (standard library). A unique disposable PostgreSQL container has no host port or shared volume and is removed in cleanup. The harness applies SQL migrations through psql; the separate postgres-it profile validates actual Flyway. It seeds 100k audit records, 50k quotes, 15k users, 5,007 rules and 100k rows in each producer outbox, captures EXPLAIN ANALYZE + BUFFERS JSON, checks identical ordered results and asserts the four intended index paths. It does not assert speed ratios. Keep rerun output separate from immutable [Phase 27 evidence](phase-27-postgres-performance-evidence.json).

## Packaged-Service E2E

Build first with `./mvnw verify`, then choose a focused harness; no existing development databases are required:

```bash
python3 infrastructure/validation/phase25-service-auth-e2e.py
python3 infrastructure/validation/phase26-resilience-e2e.py
python3 infrastructure/validation/phase27-operations-e2e.py
```

These use Docker, Python 3, a runnable `java` on PATH and packaged jars in service `target/` directories. They create isolated infrastructure, ephemeral signing material and service processes, and clean up in finally blocks. Older phase19–24 harnesses are retained as historical/focused regression tools; consult the matching validation reports before using optional browser checkpoint modes. The default Operations harness does not pause for browser input. Do not rerun every historical harness for a documentation change.

Final polish runs and any environmental failures are recorded in [portfolio validation](portfolio-validation.md). Historical [specifications](specs/README.md), `phase-*-validation.md` reports and the [SignalStore migration report](../frontend/SIGNALSTORE_MIGRATION.md) retain their original evidence rather than being rewritten to current test counts.
