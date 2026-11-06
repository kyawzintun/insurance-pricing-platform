# Phase 26 — Validation

> Historical milestone record: scope, commands, counts and limitations below describe that revision. For the completed implementation and current run instructions, start with the [root README](../README.md).

Validation date: 2026-11-03 (Asia/Bangkok). Scope is application observability/resilience only.
No Phase 27 implementation, schema/index changes, frontend behavior, event contracts or
new infrastructure services were added.

## Automated validation

| Suite | Result |
| --- | --- |
| `./mvnw -B package` | PASS: 450 unit/HTTP tests across all six services; all packaged |
| `./mvnw -B -Ppostgres-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false verify` | PASS: 57 PostgreSQL integration tests (Auth 9, Quote 20, Pricing 4, Notification 11, Audit 13) |
| `./mvnw -B -Pkafka-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false verify` | PASS: 8 Kafka integration scenarios (Auth 1, Quote 1, Pricing 1, Notification 2, Audit 3) |
| Auth PostgreSQL profile with publisher enabled and long poll/sample intervals | PASS: actual datasource startup with enabled observability |
| `python3 infrastructure/validation/phase26-resilience-e2e.py` | PASS: all six packaged services, outage/recovery, DB readiness, metrics, duplicate delivery and redaction |
| `git diff --check` | PASS |

Normal Maven tests include controlled real local HTTP Pricing/Auth stubs. New resilience tests
verify OPEN rejection without Auth/HTTP calls, short timeouts, half-open recovery and CLOSED
state, error/timer updates, and safe errors. Existing service-auth tests verify single-flight
cache reuse/expiry, one-time 401 refresh and 403 without retry. Quote HTTP tests verify token
failure prevents Pricing/persistence, successful creation/failure counters, probe access and
ADMIN-only metrics. Consumer observation tests verify processed/duplicate counters and MDC
restoration on exceptions. Outbox tests now verify successful/failed publication counters and
retain existing payload/exception redaction, acknowledgment and replay-window assertions.

The PostgreSQL suite retains real atomic quote/outbox persistence, rollback, identity, precision,
consumer marker/business-write atomicity and duplicate concurrency checks. Kafka tests retain
real outbox delivery, raw DLQ bytes/headers, failed-DLQ replay and independent consumer groups.
The final security-only Actuator allowlist adjustment was rechecked by the full 450-test build
and the packaged E2E; it does not alter database or messaging paths.

## Disposable E2E coverage

The harness starts Gateway, Auth, Quote, Pricing, Notification, Audit, PostgreSQL 17.11 and
Kafka 4.1.2 with unique containers, databases, groups/topics and ephemeral signing material.
It cleans up its own processes/containers and deletes ephemeral private keys in finally.
It uses ephemeral ports and does not touch Compose's persistent databases or offsets.

Assertions cover CUSTOMER register/login/refresh, normal quote creation, all six public
health/info/liveness/readiness probes, ADMIN metrics (Notification's internal metrics remain
public), hidden management endpoints, quote/pricing/outbox counters, cached outbox gauges,
Pricing stop → failures → OPEN → sub-800ms rejection → unchanged quote/outbox counts →
Pricing restart → half-open success → CLOSED → successful quote. Events must reach both
Notification and Audit. Replaying one unchanged outbox envelope must increment both duplicate
counters without adding business rows. A disposable DB stop must leave Quote liveness UP
while readiness returns 503; restart restores readiness. An expired service-token cache during
Auth outage must fail safely with unchanged quote/outbox counts and recover after Auth returns.

Correlation is checked in Gateway/Quote/Pricing logs. Logs are scanned for the actual customer,
admin and refresh credentials, service client secret and private-key material. Verifier jars
must not contain private-key fixtures; verifier environments receive no private-key setting.

Validation harness corrections during development: fixed relaxed-binding Hikari environment
names, restored the quote calculation fixture after narrowing the inherited Phase 25 harness,
pinned the disposable PostgreSQL host port across restarts, and explicitly denied non-allowlisted Actuator paths before generic application handling.
No failed developmental run is counted as successful evidence.

## Operational limits

See [specification and operations](specs/phase-26-observability-resilience.md). In particular:

- Counts/state are per process and reset on restart; they are not exactly-once business totals.
- Pending count/age are cached samples, with -1 for unknown; sampling scans pending rows.
- Consumer health checks local container lifecycle, not broker reachability, lag or assignment.
  Deliberately disabled listeners report UNKNOWN; database health still participates.
- Notification metrics need a private service port/network; other services require ADMIN.
- Individual HTTP exchanges have timeouts; single-flight token lock waits have no independent
  global deadline. There is no new transport retry or fallback premium.
- No Prometheus server, tracing stack, automatic outbox mutation, or Phase 27 work was added.

## Changed file inventory

- `.env.example`
- `README.md`
- `docs/architecture.md`
- `docs/implementation-roadmap.md`
- `docs/phase-26-validation.md`
- `docs/security.md`
- `docs/specs/phase-26-observability-resilience.md`
- `infrastructure/validation/phase26-resilience-e2e.py`
- `services/api-gateway/README.md`
- `services/api-gateway/src/main/java/com/insurance/platform/gateway/config/GatewaySecurityConfiguration.java`
- `services/api-gateway/src/main/java/com/insurance/platform/gateway/filter/CorrelationIdFilter.java`
- `services/api-gateway/src/main/resources/application.yml`
- `services/audit-service/README.md`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/AuditKafkaConfiguration.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/AuditSecurityConfiguration.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/ConsumerHealth.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/CorrelationLoggingFilter.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/EventObservation.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/PricingEventListener.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/QuoteAuditListener.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/QuoteDeadLetterRecoverer.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/UserEventListener.java`
- `services/audit-service/src/main/resources/application.yml`
- `services/audit-service/src/test/java/com/insurance/platform/audit/EventObservationTests.java`
- `services/audit-service/src/test/resources/application-integration.yml`
- `services/audit-service/src/test/resources/application-test.yml`
- `services/auth-service/README.md`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/AuthSecurityConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/CorrelationLoggingFilter.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/OutboxMetrics.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/event/AuthCorrelationId.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/service/AuthOutboxPublisher.java`
- `services/auth-service/src/main/resources/application.yml`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthOutboxConfigurationTests.java`
- `services/auth-service/src/test/resources/application-test.yml`
- `services/notification-service/README.md`
- `services/notification-service/src/main/java/com/insurance/platform/notification/config/ConsumerHealth.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/config/CorrelationLoggingFilter.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/config/NotificationKafkaConfiguration.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/config/Phase2SecurityConfiguration.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/EventObservation.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/QuoteDeadLetterRecoverer.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/QuoteEventListener.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/UserEventListener.java`
- `services/notification-service/src/main/resources/application.yml`
- `services/notification-service/src/test/java/com/insurance/platform/notification/EventObservationTests.java`
- `services/notification-service/src/test/resources/application-test.yml`
- `services/pricing-service/README.md`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/CorrelationLoggingFilter.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/OutboxMetrics.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/PricingSecurityConfiguration.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/event/PricingCorrelationId.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/service/PricingOutboxPublisher.java`
- `services/pricing-service/src/main/resources/application.yml`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingOutboxConfigurationTests.java`
- `services/pricing-service/src/test/resources/application-test.yml`
- `services/quote-service/README.md`
- `services/quote-service/pom.xml`
- `services/quote-service/src/main/java/com/insurance/platform/quote/client/PricingClient.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/client/ServiceTokenClient.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/config/CorrelationLoggingFilter.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/config/OutboxMetrics.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/config/PricingResilienceConfiguration.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/config/QuoteSecurityConfiguration.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/event/QuoteCorrelationId.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/service/QuoteOutboxPublisher.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/service/QuoteService.java`
- `services/quote-service/src/main/resources/application.yml`
- `services/quote-service/src/test/java/com/insurance/platform/quote/CorrelationLoggingTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/PricingResilienceTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteOutboxPublisherTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteServiceApplicationTests.java`
- `services/quote-service/src/test/resources/application-test.yml`

## Final disposable result

The final harness exited successfully (0), with these assertions passing:

- All six services: health/info/liveness/readiness UP while healthy; metrics available under
  the documented authorization policy; env/configprops/heapdump/threaddump denied.
- Three normal quotes committed. Pricing stopped: repeated safe 503s, circuit OPEN,
  subsequent request completed in under 800 ms, aggregate/outbox row counts unchanged.
- Pricing restarted: half-open probe succeeded, circuit CLOSED, successful creation resumed.
- Quote created/failed, Pricing duration/error/state, outbox published/pending/age and both
  consumer processed/duplicate metrics were observed through actual Actuator endpoints.
- Both consumers received the recovered quote; replay incremented duplicate counters and
  left business row counts unchanged.
- PostgreSQL stopped: liveness remained UP, readiness became 503. Restart on the pinned host
  port restored readiness without restarting Quote.
- Auth stopped with expired cached token: safe 503, unchanged quote/outbox counts; restart
  restored successful quote creation. Short slow-Auth timeout is separately covered by HTTP stubs.
- Existing correlation appeared in Gateway/Quote/Pricing safe logs; credential/private-key
  log scans, login/refresh RS256 checks and private-key isolation assertions passed.
- The harness removed its processes/containers and ephemeral private signing key.

Final output:

```text
PASS: all six health/info/probes/metrics; protected metrics; hidden sensitive endpoints; quote counters; pricing outage opens circuit, rejects fast without partial quote/outbox, recovers and closes; Auth outage safe failure; Notification/Audit delivery and counters; safe correlation logs.
PASS: RS256 login/refresh regression and log redaction; no private signing material in verifier jars/environments.
```

Phase 26 is complete and ready for Phase 27 — PostgreSQL Performance, subject to the
operational limits above. Phase 27 has not been started.
