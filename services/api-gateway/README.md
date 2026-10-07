# API Gateway — Phase 5

Gateway runs on **8080**, using Spring Cloud Gateway WebFlux and reactive Spring Security. Its only downstream route is `/api/v1/auth/**` → Auth Service, with the original path and Authorization header preserved. It has no database or business endpoints.

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
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:4200`; comma-separated exact origins |
| `AUTH_JWT_SECRET` | Same Base64 key as Auth; at least 32 bytes |
| `AUTH_JWT_ISSUER` | `insurance-auth-service`; must match Auth |
| `SERVER_ADDRESS` | `127.0.0.1` in local profile |

Local YAML reuses Auth's **public development-only key**. Explicitly selecting another profile removes this fallback; supply `AUTH_JWT_SECRET` securely to both processes. A missing, invalid Base64, or short key prevents startup. Environment variables override YAML; Maven does not automatically load `.env`. Gateway has no issuer-discovery network call.

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

There is **no protected business endpoint yet**. For a manual authentication check, request `/phase5-authentication-check`: without a token it returns 401; with a valid token it returns 404 because authentication passed but no route exists. This is not a production endpoint or evidence of downstream business authorization. Automated tests use a stub protected Auth path to prove authenticated forwarding. Auth itself still issues tokens and does not yet validate Bearer tokens on its protected paths; downstream JWT validation comes later.

## Validation and propagation

`config/JwtConfiguration` uses the Boot-managed Nimbus reactive decoder restricted to HS256, plus explicit issuer, expiration (zero clock skew), required expiration, nonblank subject, and nonempty string roles validation. `sub` is the principal name; roles map to `ROLE_` authorities. No role-based business policy is implemented. Auth supplies `iat`; it is not an additional required claim in Gateway.

`config/GatewaySecurityConfiguration` defines the public method/path combinations, stateless authentication, safe errors, and CORS. Only GET/POST/OPTIONS and Authorization/Content-Type/X-Correlation-ID headers are allowed for configured origins under `/api/**`; credentials are disabled. Expand methods when actual later APIs need them.

`filter/CorrelationIdFilter` accepts a 1–128 character identifier containing letters, digits, dots, underscores, or hyphens; absent/unsafe values become UUIDs. The same `X-Correlation-ID` is forwarded and returned, including authentication failures, and exposed to the browser. It is diagnostic data, not trusted identity. No tracing or custom user/role headers are introduced.

Gateway authentication is one boundary; downstream services must later validate tokens independently. There are no quote/pricing/admin routes, service discovery, Kafka handlers, refresh tokens, or rate limiting.

## Tests and troubleshooting

```bash
./mvnw -pl services/api-gateway -am verify
./mvnw clean verify
```

Tests start a real Gateway and an ephemeral local HTTP stub with a generated signing key. They require no Auth process, PostgreSQL, Kafka, or Docker. See [Phase 5 validation](../../docs/phase-5-validation.md).

For troubleshooting only, `http://localhost:8081/actuator/health` checks Auth directly; Gateway health checks Gateway itself and does not prove Auth is reachable. Confirm both applications share the key/issuer and that `AUTH_SERVICE_URL` matches Auth's port if routing fails.

Reference: [Spring Security reactive JWT resource server](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).
