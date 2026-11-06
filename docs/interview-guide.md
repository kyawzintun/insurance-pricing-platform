# Interview Guide

Each explanation is a 30–60 second speaking outline. Expand the example when asked; use the [README](../README.md) and linked evidence to show implementation rather than recite framework definitions.

## 1. System architecture

I separate immediate work from eventual side effects. Angular calls Gateway; Quote asks Pricing for a premium, commits a historical snapshot, and publishes durable event intent later. Each domain owns its database, so services exchange HTTP DTOs and events rather than tables.

**Project example:** Creating a quote returns 201 after quote/outbox commit; Notification and Audit can complete later.

## 2. Why microservices

I used microservices to make ownership and failure boundaries visible in a learning project. The cost is more processes, credentials, integration testing and operational work. For a small commercial product I would also evaluate a modular monolith rather than assume distribution is necessary.

**Project example:** Quote cannot query pricing_db; it uses Pricing’s internal HTTP contract.

## 3. Synchronous Quote → Pricing

The customer needs a price in the create response, so calculation is synchronous. I validate the result before opening the quote persistence transaction. Timeouts and a circuit breaker bound dependency failures, and I never replace a failed calculation with a made-up premium.

**Project example:** Pricing outage returns a safe 502/503 without storing a partial quote.

## 4. Kafka design

Events carry a version, event ID, aggregate identity, occurrence time and correlation ID. Aggregate UUID keys keep related records on one partition. Notification and Audit have independent consumer groups, so both can receive a quote event without competing for it. There is no global ordering guarantee.

**Project example:** QuotePriced goes to insurance.quote.events; pricing-rule changes go to insurance.pricing.events and Audit only.

## 5. Transactional outbox

A database transaction can commit successfully while a direct Kafka send fails. I store the complete event envelope with the business mutation, then poll PENDING rows. Only broker acknowledgment permits marking PUBLISHED. This closes the lost-intent gap but deliberately permits duplicate publication after a crash.

**Project example:** A quote and its QuotePriced outbox intent commit together; failed intent persistence rolls back both.

## 6. Idempotent consumers

Broker delivery and database commits are separate, so the same event can arrive again. Inside one local transaction I atomically claim a processed_events marker and write the side effect. A duplicate marker makes processing succeed without another effect. Failed work rolls the marker back too.

**Project example:** Notification receives the same eventId twice but creates only one notification, keyed by event ID and stable consumer identity.

## 7. Retry and DLQ

Transient processing failures get bounded fixed-backoff retries. Invalid supported input goes directly to the matching DLQ. Recovery preserves original bytes with allowlisted diagnostic headers, and source completion waits for DLQ acknowledgment. Recovery can duplicate DLQ records; no replay automation is claimed.

**Project example:** If DLQ publication fails, the source record remains replayable instead of being silently acknowledged.

## 8. Optimistic versus pessimistic locking

Pricing uses an optimistic version because admins can detect conflicts and retry deliberately. Customer status and refresh rotation use row locks where the operation must serialize a state transition. I choose the lock based on the invariant, not one universal concurrency mechanism.

**Project example:** Two admins using the same Pricing version yield one success and one 409; repeated disable commands yield one real status transition/event.

## 9. Refresh-token rotation

The browser holds an opaque HttpOnly refresh cookie while Angular keeps the access JWT in memory. Auth stores only a digest. Refresh locks the current token, revokes it and inserts a replacement atomically. Angular shares one refresh for concurrent 401s and restores on reload.

**Project example:** Reusing a rotated token fails; replacement persistence failure rolls back rotation. Whole-family revocation is intentionally absent.

## 10. RS256

Only Auth needs the private signing key. Other authenticated services verify with the public key, so a verifier does not need signing authority. The implementation accepts RS256, validates issuer/expiry and keeps role/ownership enforcement in services. External key configuration is supported, while JWKS and automatic rotation are out of scope.

**Project example:** Pricing’s main resources contain only a public DEV key; Auth alone has the main-resource private fixture.

## 11. Service-to-service authentication

User identity and machine identity have different permissions. Quote obtains a short-lived service JWT from Auth using client credentials and caches it with single-flight acquisition. Pricing checks the service subject, calculation scope and its audience. A customer token cannot replace this credential.

**Project example:** Only quote-service with pricing:calculate and pricing-service audience can use internal calculation; issuance bypasses Gateway.

## 12. PostgreSQL performance

I measured plans and buffers on disposable realistic data, compared identical results before/after, and added only four justified indexes. I report scan/sort changes and write costs, not a general throughput claim based on one SQL timing. Unhelpful indexes and query rewrites were deliberately avoided.

**Project example:** Audit correlation changed from scanning 100k rows to a bitmap lookup; global quote ID pagination uses an ordered index. Deep offsets and exact totals remain costly.

## 13. Observability and resilience

Logs carry bounded correlation IDs; health/readiness, counters and timers explain failures without exposing payloads. Quote has independent HTTP bounds and a Pricing circuit breaker. Gateway collects curated Operations data concurrently, preserving healthy cards when a target fails. Metrics are current-process measurements.

**Project example:** Stopping Pricing marks its Operations card DOWN and the aggregate DEGRADED while other cards remain available; recovery is observable.

## 14. Angular state architecture

Reactive Forms own user input, local Signals own presentation state, and four NgRx SignalStores own shared domain/session state. rxMethod preserves switchMap cancellation for reads and exhaustMap overlap protection for creation/refresh. Session reset cancels requests and clears protected domain state. Backend authorization remains authoritative.

**Project example:** Changing audit filters cancels a superseded read; logout clears Quote, Audit and Operations state so another session cannot inherit it.

## 15. Tradeoffs and limitations

I keep the project complete within an explicit portfolio scope. It has simulated notifications, a single local broker, static service targets and no deployment platform. At-least-once delivery and stateless JWT lifetime have visible consequences. I can explain what a real deployment would need without pretending those systems already exist.

**Project example:** An access JWT can remain usable after logout/disable until expiry; there is no exactly-once claim or production security certification.

See [architecture sequences](architecture.md), [security](security.md), [performance evidence](phase-27-postgres-performance-validation.md), [SignalStore migration](../frontend/SIGNALSTORE_MIGRATION.md) and [tradeoffs](engineering-tradeoffs.md).
