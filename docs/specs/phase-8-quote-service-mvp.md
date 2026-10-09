# Phase 8 — Quote Service MVP

Source: approved Phase 8 request. Scope: Quote Service and Gateway; preserve existing schemas, seed data, Pricing/Auth behavior, and earlier tests.

## Goal and API

POST `/api/v1/quotes` through Gateway creates one completed PRICED quote for the authenticated CUSTOMER. Input: birth date, driving experience, claims count, vehicle brand/model IDs, manufacturing year, value, engine size, and coverage. Unknown/client-owned metadata fields are rejected. No retrieval/list/edit/reprice API is added.

Gateway routes exact `/api/v1/quotes` and descendants to configurable `QUOTE_SERVICE_URL`, requiring authentication through its existing default rule. Quote independently validates HS256 signature, expiration, issuer, UUID subject, and roles, and requires CUSTOMER. ADMIN-only tokens receive 403. The UUID subject is the sole source of customer_id; there is no Auth database foreign key.

## Validation and vehicle snapshots

Field validation matches Phase 6: required values, past birth date using UTC Clock, nonnegative experience/claims, manufacturing year 1886 through current UTC year, positive vehicle value with NUMERIC(15,2) limits, positive integer engine size, and COMPREHENSIVE/THIRD_PARTY coverage. Integers are not silently truncated from fractions and enum ordinals are rejected.

Brand/model must exist, both be active, and model must belong to the selected brand. Missing references return specific 404 errors; inactive/mismatched selections return 400. Names are read from quote_db and copied into snapshots, never accepted from clients. Reference validation is a point-in-time read before Pricing; no catalog lock is held over HTTP.

## Pricing integration and errors

A small servlet-compatible Spring RestClient calls Pricing directly at configurable `PRICING_SERVICE_URL`, POST `/internal/v1/pricing/calculate`. Only the seven Phase 6 inputs are sent; no identity, catalog IDs, quote ID, or user token. Positive configurable connect/read timeouts default to 2s/5s; no retries, cache, fallback, service discovery, or duplicate pricing rules.

Connection/timeouts return safe 503 PRICING_SERVICE_UNAVAILABLE; downstream HTTP errors, malformed bodies, and unusable snapshots return 502 PRICING_SERVICE_ERROR. Validate required fields, THB, numeric precision, supported rule types, bounded lengths, contiguous unique sequences, amount-chain continuity, and final amount. Preserve Pricing's values without recomputing rule matches or factor arithmetic. Invalid pricing never reaches quote persistence.

## Transaction and snapshot design

QuoteService performs request validation, catalog reads, and remote Pricing outside a write transaction. A separate QuotePersistenceService Spring bean starts a transaction after successful pricing and saves the full cascading Quote/QuoteDriver/QuoteVehicle/PricingBreakdown/PricingAdjustment graph. A failed database write rolls back all five tables. No partial FAILED quote is saved.

UUIDs and a reference `Q-yyyyMMdd-<32 uppercase UUID hex digits>` (43 characters) are server-generated; no max+1. Quote createdAt/pricedAt/updatedAt share a captured UTC instant, truncated to PostgreSQL microseconds; expiresAt is exactly 30 days later. The Pricing calculatedAt is retained separately. Stored premium and adjustment data never depend on later rule lookups.

Existing Quote schema has no columns for basePricingRuleId, basePricingRuleVersion, or per-adjustment pricingRuleVersion. These wire fields are validated but not persisted or returned as if stored. Other supported snapshot fields are preserved. No migration is added merely to retain versions. Quote responses contain only stored snapshot data, without persistence IDs for child rows or customer identity details.

## Security and deferred work

Quote → Pricing currently uses synchronous internal REST without service credentials. Service-to-service authentication is deferred to Phase 25. Internal Pricing stays off Gateway. Local JWT key/issuer reuse the explicitly public learning configuration; non-local execution requires a supplied secret. No Kafka/outbox events, Angular, retrieval/ownership rules, expiration job, drafts, edit/reprice flow, retries, refresh tokens, asymmetric signing, or Phase 9 work.

## Tests and Definition of Done

Normal tests use repository mocks and an HTTP Pricing stub, with real Quote HTTP/security/client behavior. Cover identity, validation, catalog references, safe downstream failures/no persistence, sequence snapshots, and Gateway routing regressions without infrastructure. Opt-in `postgres-it` uses disposable PostgreSQL and existing migrations to prove mapping, precision, unique constraints, remote-call transaction separation, and rollback. Live validation uses real Auth/Pricing and seeded catalog IDs, compares direct Pricing, verifies historical immutability after a temporary rule change, checks outbox/Flyway/Kafka safety, and cleans temporary data. Root clean verify must pass for all six services.
