# Phase 7 — Pricing Administration

Source: approved Phase 7 request. Scope: Pricing Service and Gateway only, preserving Phase 6 calculations and existing schemas/seeds.

## Goal and API scope

ADMIN-only pricing rule list/get/create/full update/enable/disable. Gateway routes `/api/v1/admin/pricing/**` to configurable `PRICING_SERVICE_URL`, default localhost:8083. The internal calculation endpoint is not routed. No delete API.

Endpoints under `/api/v1/admin/pricing/rules`: GET collection, GET `/{id}`, POST collection, PUT `/{id}`, PATCH `/{id}/enable`, PATCH `/{id}/disable`. All return DTOs. Create returns 201 with Location; other successes return 200. List uses zero-based page (default 0), size 1–100 (default 20), optional ruleType/enabled, fixed ascending ID sort, and explicit pagination metadata.

## Validation and calculation compatibility

Separate create/update DTOs validate required type/operator/effectiveFrom/enabled and existing database precision/length limits. Create rejects client ID/version/timestamps. PUT requires nonnegative expected version and replaces editable fields; PATCH requires `{ "version": n }`.

Shared explicit PricingRuleValidator preserves Phase 6 semantics: positive base fixed amount, EQUALS storage placeholder and no factor/operands; adjustments require nonnegative factor with no fixed amount; numeric operators use plain-decimal operands and inclusive ordered BETWEEN; coverage only supports EQUALS with COMPREHENSIVE/THIRD_PARTY. Rule data is validated before persistence, even when disabled/future. Future and past effective dates are allowed within ISO years 0001–9999; persisted instants use PostgreSQL microsecond precision. Description remains optional, at most 255 characters.

Multiple rules per category remain valid. Phase 6 selects the newest effective matching rule with UUID tie-break and keeps its existing category order and rounding. Disabling/retyping the last active base is allowed; calculation then fails safely. No new global base-uniqueness guarantee.

## Persistence and concurrency

Writes affect only pricing_rules. Reuse existing @Version; server creates UUID/version/timestamps. PUT/PATCH compare expected version before mutation and flush Hibernate's version-checked UPDATE before mapping the response. Concurrent winners increment the version; losers receive safe 409. Repeating enable/disable at the current version advances updatedAt/version even when the enabled value already matches. createdAt/id are immutable through the API. No migrations, physical deletes, or outbox writes.

## Security

Gateway and Pricing each verify HS256 JWT signature, expiration, issuer, subject, and roles, then require ADMIN. CUSTOMER gets 403; missing/invalid JWT gets 401. Authorization remains unchanged in transit. All three services share current local development key/issuer conventions; non-local configuration requires supplied key. No trusted user headers, asymmetric JWT, or service-to-service credentials. Internal calculation remains temporarily public directly in Pricing until Phase 25. CORS adds PUT/PATCH for existing configured origins.

## Errors

400 INVALID_PRICING_RULE; 401 UNAUTHORIZED; 403 FORBIDDEN; 404 PRICING_RULE_NOT_FOUND; 409 PRICING_RULE_VERSION_CONFLICT. Safe generic 500 for configuration/database/unexpected failures, retaining Phase 6 calculation error behavior. No SQL/JWT internals or stack traces in responses.

## Tests and Definition of Done

Normal Maven tests cover HTTP authorization, validation, DTO metadata, pagination/filtering, get/missing, update/toggle/stale conflicts, Gateway forwarding and internal-route exclusion, and all Phase 6 regressions without live infrastructure. Opt-in `postgres-it` uses isolated PostgreSQL/Testcontainers to prove persisted versions and concurrent JPA conflict behavior. Live validation uses real Auth-issued ADMIN/CUSTOMER tokens through 8080 and verifies direct Pricing authorization, CRUD, filters, stale versions, effective/disabled calculation behavior, and database/Kafka safety. Temporary validation accounts/rules are cleaned up without seed edits.

No UI, Quote integration, Kafka/outbox events, cache, refresh tokens, or Phase 8 work. See [admin guide](../../services/pricing-service/README.md) and [validation](../phase-7-validation.md).
