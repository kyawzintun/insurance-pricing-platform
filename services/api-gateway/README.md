# API Gateway — Auth, Pricing Administration, and Quotes

Gateway runs on **8080**, using Spring Cloud Gateway WebFlux and reactive Spring Security. Its downstream routes are `/api/v1/auth/**` → Auth Service and `/api/v1/admin/pricing/**` → Pricing Service, plus `/api/v1/quotes` and `/api/v1/quotes/**` → Quote Service, with the original path and Authorization header preserved. It has no database or business endpoints.

## Run locally

From the repository root, start infrastructure and Auth:

```bash
docker compose up -d
./mvnw -pl services/auth-service spring-boot:run
```

In a second terminal:

```bash
./mvnw -pl services/api-gateway spring-boot:run
```

Both applications default to `local`. No launcher or environment exports are needed for the existing local setup (PostgreSQL on 15432). Stop each application with Ctrl+C. Maven builds do not leave services running.

| Setting | Default / purpose |
| --- | --- |
| `API_GATEWAY_PORT` | `8080` |
| `AUTH_SERVICE_URL` | `http://localhost:8081`; change if Auth runs elsewhere |
| `QUOTE_SERVICE_URL` | `http://localhost:8082`; Quote route |
| `PRICING_SERVICE_URL` | `http://localhost:8083`; admin Pricing route only |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:4200`; comma-separated exact origins |
| `AUTH_JWT_SECRET` | Same Base64 key as Auth; at least 32 bytes |
| `AUTH_JWT_ISSUER` | `insurance-auth-service`; must match Auth |
| `SERVER_ADDRESS` | `127.0.0.1` in local profile |

Local YAML reuses Auth's **public development-only key**, also used by Pricing and Quote. Explicitly selecting another profile removes this fallback; supply `AUTH_JWT_SECRET` securely to Auth, Gateway, Pricing, and Quote. A missing, invalid Base64, or short key prevents startup. Environment variables override YAML; Maven does not automatically load `.env`. Gateway has no issuer-discovery network call.

## Requests

Register and log in through Gateway (fictional example data; choose your own learning password):

```bash
curl -i http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"alex@example.com","password":"learning-example-password","firstName":"Alex","lastName":"Morgan"}'

curl -i http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alex@example.com","password":"learning-example-password"}'

curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/info
```

Only **POST** register/login and **GET** health/info are public. All other requests require authentication. Valid CORS preflight requests are handled before authentication.

Copy `accessToken` from login into Postman's Bearer Token field. For curl, the header syntax is `-H 'Authorization: Bearer <accessToken>'` (replace the placeholder). Missing, malformed, expired, tampered, or wrong-issuer tokens receive HTTP 401:

```json
{"code":"UNAUTHORIZED","message":"Authentication is required"}
```

An invalid Bearer token supplied to a public endpoint is also rejected: omit Authorization for registration/login. No form login, HTTP Basic, session, or generated user is enabled.

Phase 7 now adds protected ADMIN pricing-rule endpoints; see below. For a manual authentication check, request `/phase5-authentication-check`: without a token it returns 401; with a valid token it returns 404 because authentication passed but no route exists. This is not a production endpoint or evidence of downstream business authorization. Automated tests use a stub protected Auth path to prove authenticated forwarding. Auth itself still issues tokens and does not yet validate Bearer tokens on its protected paths; Pricing and Quote validate downstream JWTs independently.

## Validation and propagation

`config/JwtConfiguration` uses the Boot-managed Nimbus reactive decoder restricted to HS256, plus explicit issuer, expiration (zero clock skew), required expiration, nonblank subject, and nonempty string roles validation. `sub` is the principal name; roles map to `ROLE_` authorities. Phase 7 requires ROLE_ADMIN for `/api/v1/admin/pricing/**`; other authenticated requests retain the previous rules. Auth supplies `iat`; it is not an additional required claim in Gateway.

`config/GatewaySecurityConfiguration` defines the public method/path combinations, stateless authentication, safe errors, and CORS. Only GET/POST/PUT/PATCH/OPTIONS and Authorization/Content-Type/X-Correlation-ID headers are allowed for configured origins under `/api/**`; credentials are disabled. Expand methods when actual later APIs need them.

`filter/CorrelationIdFilter` accepts a 1–128 character identifier containing letters, digits, dots, underscores, or hyphens; absent/unsafe values become UUIDs. The same `X-Correlation-ID` is forwarded and returned, including authentication failures, and exposed to the browser. It is diagnostic data, not trusted identity. No tracing or custom user/role headers are introduced.

Gateway authentication is one boundary: Pricing independently requires ADMIN for administration; Quote independently requires CUSTOMER for creation. Internal calculation remains off Gateway. There is no service discovery, Kafka handler, refresh-token flow, or rate limiting.

## Tests and troubleshooting

```bash
./mvnw -pl services/api-gateway -am verify
./mvnw clean verify
```

Tests start a real Gateway and an ephemeral local HTTP stub with a generated signing key. They require no Auth process, PostgreSQL, Kafka, or Docker. See [Phase 5 validation](../../docs/phase-5-validation.md).

For troubleshooting only, `http://localhost:8081/actuator/health` checks Auth directly; Gateway health checks Gateway itself and does not prove Auth is reachable. Confirm both applications share the key/issuer and that `AUTH_SERVICE_URL` matches Auth's port if routing fails.

Reference: [Spring Security reactive JWT resource server](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).

## Phase 7 — Pricing administration

Start Pricing in another terminal with `./mvnw -pl services/pricing-service spring-boot:run`. Use an ADMIN access token from Auth to call `/api/v1/admin/pricing/rules` through port 8080. Gateway routes only the admin pricing prefix to `PRICING_SERVICE_URL`; it does not route `/internal/v1/pricing/calculate`.

GET list/get, POST create, PUT update, and PATCH enable/disable are supported. Gateway checks ADMIN before forwarding, preserving the original Bearer token. Pricing validates that token independently and repeats ADMIN authorization. Missing/invalid token returns 401; CUSTOMER returns 403 at either boundary. No trusted user headers or service-to-service OAuth2 are introduced.

CORS now permits PUT/PATCH in addition to GET/POST/OPTIONS for the existing configured origins, without credentials. There is no Angular admin UI yet. See [Pricing API examples, pagination and versions](../pricing-service/README.md#phase-7--admin-pricing-rules), [Phase 7 specification](../../docs/specs/phase-7-pricing-administration.md), and [validation](../../docs/phase-7-validation.md). No Kafka/outbox events are emitted.

## Phase 8 — Quote creation

Start Pricing and Quote in separate terminals using `./mvnw -pl services/pricing-service spring-boot:run` and `./mvnw -pl services/quote-service spring-boot:run`. POST `/api/v1/quotes` requires a valid JWT at Gateway; Quote verifies that token again and requires CUSTOMER. ADMIN-only tokens receive 403 from Quote. No customer identity headers are synthesized. The Quote route preserves the path and Authorization header and uses configurable `QUOTE_SERVICE_URL`.

Only creation is implemented; matching a route prefix does not add retrieval/history endpoints. See [Quote request/response examples](../quote-service/README.md) and [Phase 8 validation](../../docs/phase-8-validation.md).
