# Local Development

Use JDK 25, Docker Compose with its daemon running, and the Node/npm versions in [frontend/package.json](../frontend/package.json). Maven Wrapper is checked in. Python 3 is needed for disposable E2E/performance harnesses. All shell commands here start at the repository root unless a directory change is shown.

## Infrastructure and Applications

```bash
cp .env.example .env
docker compose up -d
docker compose ps -a
docker compose logs kafka-init
```

PostgreSQL and Kafka must become healthy, and kafka-init must exit 0. Compose starts no application containers. PostgreSQL is localhost:15432, Kafka localhost:29092. Five databases are created only on first initialization of a fresh PostgreSQL volume. Service-owned Flyway applies schema/seed migrations at application startup. Reference data and educational pricing rules are seeded; users are not.

Run each application in a separate terminal:

```bash
./mvnw -pl services/auth-service spring-boot:run
./mvnw -pl services/pricing-service spring-boot:run
./mvnw -pl services/notification-service spring-boot:run
./mvnw -pl services/audit-service spring-boot:run
./mvnw -pl services/quote-service spring-boot:run
./mvnw -pl services/api-gateway spring-boot:run
```

Auth/Pricing must be ready before quote creation. Consumer startup before creating data makes the asynchronous result easier to observe. Every service exposes `/actuator/health` and `/actuator/health/readiness` on its own port (8080–8085). Readiness includes the owning database, and consumers include their local Kafka listener state; it is not a complete end-to-end delivery guarantee. Gateway readiness reports local availability.

```bash
cd frontend
npm ci
npm start
```

If port 4200 is already occupied, reuse the existing project server or run `npm start -- --port 4201`; an alternate browser origin must also be allowed by Gateway for authenticated API calls.

Open http://localhost:4200 using `localhost` consistently (not a mixture of localhost and 127.0.0.1) for cookie/site behavior. The frontend API URL is compiled from `frontend/src/environments/environment.ts`; restart/rebuild after changes. It is public configuration, never a secret.

Stop application terminals with Ctrl-C. `docker compose down` preserves named volumes; restarting reuses data. `down -v` destroys data and is not a normal setup step. For initialization or migration trouble, see [infrastructure troubleshooting](../infrastructure/README.md) and the [intentional rebuild procedure](../services/README.md#intentional-local-database-rebuild).

## Environment Reference

Compose reads `.env`; Maven-started processes do not. Export only overrides needed by each application, for example `export POSTGRES_PORT=15433` in its terminal if you changed Compose's published port. The example file is a reference, not a shell script; do not source it wholesale. Avoid overriding local service credentials with placeholders.

| Group | Variables / defaults | Scope |
| --- | --- | --- |
| PostgreSQL | `POSTGRES_HOST=localhost`, `POSTGRES_PORT=15432`, `POSTGRES_USER=insurance_dev`, `POSTGRES_PASSWORD=local_dev_only` | Five DB services; public DEV-only account/password |
| Kafka | `KAFKA_EXTERNAL_PORT=29092`; optional `KAFKA_BOOTSTRAP_SERVERS` | Local apps derive bootstrap from external port; Gateway has no broker connection |
| Service ports | `API_GATEWAY_PORT`, `AUTH_SERVICE_PORT`, `QUOTE_SERVICE_PORT`, `PRICING_SERVICE_PORT`, `NOTIFICATION_SERVICE_PORT`, `AUDIT_SERVICE_PORT` | Defaults 8080–8085; `SERVER_ADDRESS=127.0.0.1` locally |
| HTTP targets | `AUTH_SERVICE_URL`, `QUOTE_SERVICE_URL`, `PRICING_SERVICE_URL`, `AUDIT_SERVICE_URL`, `NOTIFICATION_SERVICE_URL` | Gateway targets; Quote also uses Pricing URL |
| RSA public key | `AUTH_JWT_PUBLIC_KEY=file:/absolute/path/public.pem` | Auth, Gateway, Quote, Pricing, Audit; local default public fixture |
| RSA private key | `AUTH_JWT_PRIVATE_KEY=file:/absolute/path/private.pem` | Auth only; external outside local |
| User JWT | `AUTH_JWT_ISSUER=insurance-auth-service`, `AUTH_JWT_ACCESS_TOKEN_TTL=15m` | Issuer consistent across verifiers |
| Refresh cookie | `AUTH_REFRESH_TOKEN_TTL=7d`, `AUTH_REFRESH_COOKIE_NAME=refresh_token`, `AUTH_REFRESH_COOKIE_SAME_SITE=Lax`, `AUTH_REFRESH_COOKIE_SECURE` | Auth; Secure true normally, false in local HTTP profile |
| Service credentials | `QUOTE_SERVICE_CLIENT_ID=quote-service`, `QUOTE_SERVICE_CLIENT_SECRET` | Auth/Quote only; local public DEV secret supplied by local YAML |
| Service token | `SERVICE_TOKEN_TTL=5m`, `SERVICE_TOKEN_URL=http://localhost:8081/api/v1/auth/service-token`, `SERVICE_TOKEN_EXPIRY_MARGIN=30s` | TTL Auth; URL/margin Quote |
| Frontend/CORS | `GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:4200`; Angular build-time `apiBaseUrl` | Exact origins; credentials enabled |
| HTTP bounds | `AUTH_TOKEN_CONNECT_TIMEOUT=2s`, `AUTH_TOKEN_READ_TIMEOUT=5s`, `PRICING_CONNECT_TIMEOUT=2s`, `PRICING_READ_TIMEOUT=5s` | Quote |
| Gateway bounds | `GATEWAY_HTTP_CONNECT_TIMEOUT_MS=2000`, `GATEWAY_HTTP_RESPONSE_TIMEOUT=30s`, `OPERATIONS_HEALTH_TIMEOUT_MS=1000` | Gateway; Operations per exchange |
| Circuit breaker | `PRICING_CB_FAILURE_RATE_THRESHOLD=50`, `PRICING_CB_MIN_CALLS=5`, `PRICING_CB_SLIDING_WINDOW_SIZE=10`, `PRICING_CB_OPEN_WAIT=10s` | Quote; Pricing dependency only |
| Expiration | `QUOTE_EXPIRATION_ENABLED=true`, `QUOTE_EXPIRATION_POLL_INTERVAL=60000`, `QUOTE_EXPIRATION_BATCH_SIZE=50` | Quote; interval milliseconds |
| Outbox | `OUTBOX_PUBLISHER_ENABLED`, `AUTH_OUTBOX_PUBLISHER_ENABLED`, `PRICING_OUTBOX_PUBLISHER_ENABLED` | Respective services; true local, false otherwise |
| Consumer retry | `KAFKA_CONSUMER_MAX_ATTEMPTS=3`, `KAFKA_CONSUMER_BACKOFF_MS=1000` | Notification/Audit |

Topic, consumer-group and outbox poll/batch overrides are documented in [Kafka](kafka-design.md), [outbox](transactional-outbox.md), [user](user-registered-event.md) and [pricing](pricing-rule-events.md) guides. Changing topic names requires compatible topic/DLQ provisioning. Non-local deployment also needs explicit datasource/broker/server configuration and a deliberate publisher opt-in; these host-local instructions are not a deployment recipe.

## Admin Provisioning (DEV ONLY)

No demo password or user is seeded. Register a dedicated account with email `local-admin@example.com` using the UI and a password you choose. Then explicitly promote that one account in your own local database:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d auth_db -v ON_ERROR_STOP=1' <<'SQL'
UPDATE users
SET role = 'ADMIN', updated_at = CURRENT_TIMESTAMP
WHERE email = 'local-admin@example.com'
  AND role = 'CUSTOMER'
  AND status = 'ACTIVE'
RETURNING id, email, role;
SQL
```

Check that exactly the intended account was returned; an already-promoted account returns zero rows. Log in again for the ADMIN claim. This direct SQL is a DEV-only bootstrap, not a public endpoint, event-producing business command or production provisioning system. ADMIN pages are Audit and Operations. Pricing/customer administration is API-only; use the service guides for DTOs/curl examples.

## Quick Demonstration

1. Register/log in as CUSTOMER; create a quote using seeded brand/model choices.
2. Open history/detail, reload to demonstrate refresh restoration, then log out.
3. Log in as your promoted ADMIN; inspect Audit and filter by a quote correlation/entity.
4. Open Operations and use manual/automatic refresh. It displays current process counters, not stored monitoring history.

Do outage/poison-message demonstrations with disposable harnesses rather than altering shared development data. See [testing](testing.md) for safe validation commands.
