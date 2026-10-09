# Quote Service — Phase 8 MVP

Creates completed car-insurance quotes using quote_db vehicle reference data and a synchronous call to Pricing Service. It stores historical driver, vehicle, and pricing snapshots atomically. Pricing values are educational, not actuarial.

## Local startup

With the existing PostgreSQL/Kafka infrastructure running, start each application in its own terminal:

```bash
./mvnw -pl services/auth-service spring-boot:run
./mvnw -pl services/pricing-service spring-boot:run
./mvnw -pl services/quote-service spring-boot:run
./mvnw -pl services/api-gateway spring-boot:run
```

These are separate foreground commands; Ctrl+C stops each application. Quote defaults to local, loopback port **8082**, and PostgreSQL **localhost:15432/quote_db**, matching this project's current learning setup. No launcher script is needed. Database host/port/user/password and service port remain overridable. Maven does not automatically load `.env`; a fresh Compose setup on 5432 requires changing/exporting POSTGRES_PORT appropriately.

| Configuration | Default |
| --- | --- |
| `QUOTE_SERVICE_PORT` | `8082` |
| `QUOTE_SERVICE_URL` (Gateway) | `http://localhost:8082` |
| `PRICING_SERVICE_URL` (Quote client) | `http://localhost:8083` |
| `PRICING_CONNECT_TIMEOUT` | `2s` |
| `PRICING_READ_TIMEOUT` | `5s` |
| `AUTH_JWT_ISSUER` | `insurance-auth-service` |
| `AUTH_JWT_SECRET` | Shared public development-only local key; required outside local |

Quote independently validates HS256 signature, expiration (zero skew), issuer, UUID subject, and roles. CUSTOMER is required for creation; ADMIN-only receives 403. Missing/invalid token returns 401. Auth/Gateway/Quote must share the key/issuer. Gateway checks authentication and forwards the original Bearer token. Quote derives customer_id only from JWT sub; custom identity headers and request fields are not trusted.

## Create a quote

**POST `http://localhost:8080/api/v1/quotes`**

Use a CUSTOMER access token from login through Gateway. The following IDs are the existing seeded Toyota/Camry pair:

```bash
curl -i http://localhost:8080/api/v1/quotes \
  -H 'Authorization: Bearer <CUSTOMER_ACCESS_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{
    "dateOfBirth":"1995-04-20",
    "drivingExperienceYears":8,
    "previousClaimsCount":1,
    "vehicleBrandId":"f0a67386-c7ae-562e-ae46-9fe3042cf38e",
    "vehicleModelId":"7966e042-1127-5b17-8e0c-a6799b4e67d0",
    "vehicleManufacturingYear":2021,
    "vehicleValue":700000.00,
    "engineSizeCc":1500,
    "coverageType":"COMPREHENSIVE"
  }'
```

Success is **201 Created**. Example using unchanged seeds on 2026-10-08 (IDs/timestamps are illustrative):

```json
{
  "id":"00000000-0000-4000-8000-000000000001",
  "quoteReference":"Q-20261008-00000000000040008000000000000001",
  "status":"PRICED",
  "coverageType":"COMPREHENSIVE",
  "driver":{"dateOfBirth":"1995-04-20","drivingExperienceYears":8,"previousClaimsCount":1},
  "vehicle":{
    "brandId":"f0a67386-c7ae-562e-ae46-9fe3042cf38e","brandName":"Toyota",
    "modelId":"7966e042-1127-5b17-8e0c-a6799b4e67d0","modelName":"Camry",
    "manufacturingYear":2021,"vehicleValue":700000.00,"engineSizeCc":1500
  },
  "pricing":{
    "basePremium":8000.00,"finalPremium":11200.00,"currency":"THB",
    "calculatedAt":"2026-10-08T10:00:00Z",
    "adjustments":[{
      "pricingRuleId":"e8b09cf1-c3f3-57e3-a1fb-1dc907680679","ruleType":"COVERAGE_TYPE",
      "description":"Learning-only COVERAGE_TYPE rule","inputValue":"COMPREHENSIVE",
      "factor":1.4000,"amountBefore":8000.00,"amountAfter":11200.00,"sequenceNumber":1
    }]
  },
  "createdAt":"2026-10-08T10:00:00Z",
  "pricedAt":"2026-10-08T10:00:00Z",
  "expiresAt":"2026-11-07T10:00:00Z"
}
```

There is no GET-by-ID/history endpoint or Location link implying an implemented retrieval API. No editing, draft, reprice, or expiration job exists yet. Repeating POST creates a new quote; idempotency/retry behavior is not implemented in this phase.

## Validation and catalog references

All fields are required. Birth date must be before today's UTC date; experience/claims must be nonnegative integers; manufacturing year must be 1886 through the current UTC year; vehicle value must be positive with at most 13 integer/two fractional digits; engine size must be positive. Coverage is COMPREHENSIVE or THIRD_PARTY. Unknown fields—including customerId, quoteId, status, premium, pricing, createdAt, expiresAt, and vehicle names—are rejected. Integer fractions and numeric enum values are rejected.

Vehicle brand/model are read from quote_db. Both must be active, and the model must belong to the brand. Names are copied from reference records into quote_vehicles. This is a point-in-time catalog check; no catalog locks are held across the remote call. Catalog/admin changes after creation cannot change the stored vehicle names.

## Pricing and transaction flow

1. Authenticate and validate input.
2. Read/validate catalog references and capture vehicle names.
3. Call Pricing directly, POST `/internal/v1/pricing/calculate`, with only its seven expected input fields.
4. Validate the returned snapshot's required fields, THB currency, lengths/decimal precision, sequence and amount-chain consistency.
5. Enter a separate persistence transaction, save the complete quote graph, and commit before returning 201.

The HTTP client uses [Spring RestClient with JDK HTTP request factory](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/client/JdkClientHttpRequestFactory.html), bounded configurable positive connection/read timeouts, and no retries/fallback. It sends neither the customer's token nor customer/catalog/quote IDs. **Quote → Pricing currently uses synchronous internal REST without service credentials. Real service-to-service authentication remains deferred to Phase 25.** Internal Pricing is not exposed through Gateway.

No database write transaction is held during Pricing HTTP. No partial/FAILED quote is saved if Pricing fails. All writes to quotes, quote_drivers, quote_vehicles, pricing_breakdowns, and pricing_adjustments succeed or roll back together. Pricing calculations are not duplicated in Quote: the client checks snapshot integrity, not rule applicability or factor arithmetic.

## Snapshot details

Quote IDs are UUIDs. Reference format `Q-yyyyMMdd-<32 uppercase UUID hex digits>` is 43 characters, under VARCHAR(50), with the existing database unique constraint. No max+1 query. Quote createdAt/pricedAt/updatedAt share one UTC instant captured for persistence; expiresAt is exactly 30 × 24 hours later. Precision is truncated to PostgreSQL microseconds. Pricing's calculatedAt is stored separately.

The stored premium and adjustments are independent of later Pricing rule changes. The existing schema cannot store **basePricingRuleId, basePricingRuleVersion, or per-adjustment pricingRuleVersion**. Those fields are validated from the Pricing wire response but intentionally omitted from persisted/returned Quote snapshots. Adjustment pricing_rule_id is retained as a scalar reference, without a cross-service foreign key. No schema was changed to add version columns.

## Safe errors

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_QUOTE_REQUEST | Invalid, missing, malformed, or unknown input |
| 400 | INVALID_VEHICLE_SELECTION | Inactive reference or model/brand mismatch |
| 401 | UNAUTHORIZED | Missing/invalid JWT, including non-UUID subject |
| 403 | FORBIDDEN | Missing CUSTOMER authority |
| 404 | VEHICLE_BRAND_NOT_FOUND / VEHICLE_MODEL_NOT_FOUND | Unknown catalog ID |
| 502 | PRICING_SERVICE_ERROR | Pricing HTTP error or unusable response |
| 503 | PRICING_SERVICE_UNAVAILABLE | Connection failure or timeout |
| 500 | INTERNAL_ERROR | Unexpected persistence/processing failure; transaction rolls back |

Bodies contain only code/message. Downstream bodies/URLs, SQL, stack traces, and JWT internals are not returned.

## Code reading and tests

Read CreateQuoteRequest → QuoteController → QuoteService → PricingClient → QuotePersistenceService → entities/repositories → QuoteResponse. QuoteService orchestrates without @Transactional; the separate persistence bean creates the transaction through Spring's proxy. Repositories expose only catalog lookup and aggregate save, no Phase 9 queries. Entities are never serialized directly.

```bash
./mvnw -pl services/quote-service -am test
./mvnw clean verify
# Optional real PostgreSQL tests; requires Docker, uses a disposable database:
./mvnw -pl services/quote-service -am -Ppostgres-it verify
```

Normal tests use mocked repositories and a real HTTP Pricing stub; they need no Docker, PostgreSQL, Kafka, or H2. The optional profile applies existing Flyway migrations in PostgreSQL and checks atomic persistence, precision, uniqueness, rollback, and transaction boundaries. See [Phase 8 specification](../../docs/specs/phase-8-quote-service-mvp.md) and [validation](../../docs/phase-8-validation.md).

Phase 9 — Quote Retrieval and Ownership has not started. No Kafka/outbox events, UI, service discovery, cache, refresh tokens, or service-to-service OAuth2 were added.
