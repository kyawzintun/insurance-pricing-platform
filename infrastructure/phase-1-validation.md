# Phase 1 Validation

Status: Complete. Executed on 2026-10-02 with Docker 29.8.0 and Docker Compose 5.5.1 on macOS (ARM64 images).

## Environment

The existing `order-payment` project occupied ports 5432 and 9092. The initial startup reported a port conflict; it did not alter that project. An ignored local `.env` now sets `POSTGRES_PORT=15432` and `KAFKA_EXTERNAL_PORT=29092`. Startup then succeeded. Committed defaults remain 5432 and 9092.

Actual validated host connections:

- PostgreSQL: `localhost:15432`, user `insurance_dev`, public development-only password `local_dev_only`.
- Kafka: `localhost:29092`.
- Compose network: `postgres:5432` and `kafka:19092`.

No real secrets were added. `.env` is ignored; `.env.example` is eligible for Git. No Phase 1 commit was created as part of implementation.

## Executed Checks

| Command / check | Result |
| --- | --- |
| `docker compose config --quiet` | Passed. |
| `bash -n infrastructure/kafka/create-topics.sh` | Passed. |
| `docker compose up -d` | Passed after resolving occupied host ports with local overrides. |
| `docker compose ps -a` | PostgreSQL and Kafka healthy; `kafka-init` exited 0. |
| Host `psql -h localhost -p 15432 -U insurance_dev -d postgres` with `SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname;` | All five service databases present, plus standard administrative `postgres` database. |
| `psql` query of `pg_tables`, excluding `pg_catalog` and `information_schema`, in each service database | Zero user tables in every database. |
| Kafka `kafka-topics.sh --bootstrap-server kafka:19092 --describe` | All six required topics present; 3 partitions and replication factor 1 each. |
| `docker compose run --rm kafka-init` | Passed on existing topics, exit 0. |
| Host Kafka `kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic 'insurance.*'` | Passed; confirms advertised host listener works with a Kafka client. |
| Host `kafka-console-producer.sh` and `kafka-console-consumer.sh` on `insurance.user.events` | Produced and consumed `phase1-persistence-smoke-20261002`. Producer used `acks=all`; consumer used `--from-beginning --max-messages 1 --timeout-ms 15000`. |
| `docker compose down` followed by `docker compose up -d` | Passed without removing volumes; both services healthy and initializer exited 0 again. |
| Post-restart PostgreSQL database listing and logs | All five databases remain; logs confirm existing data was reused and initialization skipped. |
| Pre-/post-restart Kafka topic metadata comparison | All six topic IDs, partition counts, leaders, and replicas unchanged. |
| Post-restart container Kafka consumer using `kafka:19092` | Read the original persisted marker; confirms internal listener and record persistence. |
| `git diff --check` and repository scope inspection | Passed; no application code, business schemas, or migrations added. |

The host Kafka CLI was copied from the pinned Kafka container to a temporary directory for validation. It is not a repository dependency. The host `psql` installation was used for validation only; neither host CLI is a prerequisite for starting infrastructure. Equivalent container commands are documented in [README.md](README.md).

## Limitations and Deferred Work

This is local-only infrastructure: a single Kafka broker, replication factor 1, plaintext listeners, and a shared PostgreSQL bootstrap account. The account does not enforce per-service isolation. PostgreSQL initialization runs only for an empty volume, and topic initialization preserves rather than repairs existing topics. Kafka retention still applies to persisted messages.

One infrastructure smoke-test record remains in `insurance.user.events`; no application event contract is implied. Kafka's internal consumer-offset topic was created by the CLI test.

Spring Boot service skeletons are deferred to Phase 2. Angular, business tables, Flyway, authentication, gateway routing, application producers/consumers, retry/DLQ handling, idempotency, and Transactional Outbox remain for later phases. Infrastructure is left running.
