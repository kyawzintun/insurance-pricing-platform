# Local Infrastructure

Phase 1 contains only PostgreSQL, Kafka, and a one-shot Kafka topic initializer. Run commands below from the repository root. See the root [README](../README.md) for connection details and optional `.env` overrides.

## Initialization and Ownership

- `postgres/init-databases.sql` creates the five logical databases, with no business tables. The official PostgreSQL entrypoint runs it only when its data volume is empty. Changing this file or the environment credentials does not modify an existing database cluster.
- `kafka/create-topics.sh` runs after the Kafka metadata health check succeeds. It creates the three domain topics and their DLQs with 3 partitions and replication factor 1. Repeating it is safe; it does not repair incorrectly configured existing topics.
- `docker/` remains reserved for future shared Docker support.
- Named volumes store PostgreSQL data and Kafka logs/metadata. Keep the Kafka cluster ID stable with its volume; it is an identifier, not a credential.
- Future business tables and Flyway migrations belong in the owning Spring Boot service. No cross-service database access is allowed.

The pinned images are `postgres:17.11-bookworm` and `apache/kafka:4.1.2`. This is a single-machine learning environment with public development credentials and plaintext Kafka, not a production deployment.

## Verify Readiness and Databases

```bash
docker compose config --quiet
docker compose up -d
docker compose ps -a
docker compose logs kafka-init
# Successful initialization ends with kafka-init exited (0).
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname;"'
docker compose exec -T postgres bash <<'SH'
set -e
for db in auth_db quote_db pricing_db notification_db audit_db; do
  echo "$db"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$db" <<'SQL'
SELECT schemaname, tablename FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema');
SQL
done
SH
```

The listing includes the five service databases and the standard `postgres` administrative database. Each service database should have zero user tables during Phase 1.

## Verify Topics and Repeat Initialization

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe
docker compose run --rm kafka-init
```

Each of the six application topics must show `PartitionCount: 3` and `ReplicationFactor: 1`. The repeat initialization should exit successfully. Kafka may also create its own internal topics, such as `__consumer_offsets`, after CLI consumer tests.

## Optional CLI Smoke Test

This writes one plain-text infrastructure test record to a domain topic; no event schema or application producer is defined yet.

```bash
printf '%s\n' 'phase1-smoke-test' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic insurance.user.events
# A fresh consumer group starts from the beginning; inspect the output for the marker.
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic insurance.user.events --from-beginning --timeout-ms 10000
```

The bounded consumer may report a timeout after printing available records; verify that the marker appears. These commands test the Docker-network listener. Host-installed Kafka CLI tools can instead use `--bootstrap-server localhost:9092` (or your overridden external port).

## Persistence Check

After verifying the databases/topics (and optionally writing a smoke-test marker):

```bash
docker compose down
docker compose up -d
```

Wait for both health checks and successful topic initialization again. Repeat the database and topic checks and consume the earlier marker to verify record persistence. Do not use `down -v` for this check: that deletes the data volumes.

## Troubleshooting

- For startup failures, inspect `docker compose logs postgres kafka kafka-init` and `docker compose ps -a`. A healthy broker alone does not guarantee that the topic job succeeded.
- If a host port is occupied, set `POSTGRES_PORT` or `KAFKA_EXTERNAL_PORT` in `.env` and run `docker compose up -d` again. Container addresses remain unchanged.
- Host clients use `localhost`; containers on the Compose network use `postgres` / `kafka`. An unrelated container must join that network first.
- Initialization scripts do not re-run against an existing PostgreSQL volume. If initialization was interrupted, inspect logs and existing databases before taking corrective action. Do not delete volumes to troubleshoot data you need.
- PostgreSQL username/password changes in `.env` only apply automatically on first initialization. Update an existing cluster deliberately if credentials must change.
- Topic creation preserves existing topics. A wrong partition count or replication factor must be investigated explicitly; the script intentionally does not delete topics or their data.
