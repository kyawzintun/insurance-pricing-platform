# Phase 25 — Service-to-Service Authentication

> Historical milestone record: scope, commands, counts and limitations below describe that revision. For the completed implementation and current run instructions, start with the [root README](../../README.md).

The only existing synchronous backend call, Quote -> Pricing, now uses a focused
client-credentials-style exchange. No full OAuth2 Authorization Server, database
client registry, new Kafka event, service refresh token, or Phase 26 work is added.

```text
CUSTOMER
   | user JWT
   v
Gateway -> Quote Service
              | client_id + secret (HTTP Basic, form grant_type=client_credentials)
              v
          Auth Service
              | RS256 service JWT
              v
          Quote Service
              | Bearer service JWT, original X-Correlation-ID
              v
          Pricing Service
              | quote-service + pricing:calculate + pricing-service audience
              v
          Pricing calculation
```

## Exchange and claims

POST `/api/v1/auth/service-token` directly to Auth with HTTP Basic client credentials
and `Content-Type: application/x-www-form-urlencoded` body
`grant_type=client_credentials`. It requires no user JWT. Missing/malformed/wrong
credentials return the same safe 401 `invalid_client`; unsupported or missing grant
with authenticated credentials returns 400 `unsupported_grant_type`. Responses use
`Cache-Control: no-store`; success also uses `Pragma: no-cache`, with JSON fields
`access_token`, `token_type=Bearer`, `expires_in`. No cookie, refresh row or event is
created. No credentials go in query parameters. Gateway routes/security are unchanged;
Quote calls Auth directly and internal Pricing remains absent from Gateway routes.

Auth reuses the Phase 24 encoder, RSA pair, issuer and explicit RS256 algorithm.
Claims: `sub=quote-service`, `iss=insurance-auth-service` (configured issuer), `iat`,
`exp`, `token_use=service`, `scope=["pricing:calculate"]`,
`aud=["pricing-service"]`. No user roles, secret or refresh token is included.
TTL defaults to five minutes and must be a positive whole number of seconds.
User login/refresh claims and lifetimes are unchanged.

## Configuration and isolation

| Environment | Auth | Quote | Pricing/Gateway/frontend |
| --- | --- | --- | --- |
| `QUOTE_SERVICE_CLIENT_ID` | expected ID, default quote-service | own ID, default quote-service | absent |
| `QUOTE_SERVICE_CLIENT_SECRET` | expected secret | own secret | absent |
| `SERVICE_TOKEN_TTL` | default 5m | — | — |
| `SERVICE_TOKEN_URL` | — | default http://localhost:8081/api/v1/auth/service-token | — |
| `SERVICE_TOKEN_EXPIRY_MARGIN` | — | default 30s | — |
| `AUTH_JWT_PRIVATE_KEY` | private signing resource | absent | absent |
| `AUTH_JWT_PUBLIC_KEY` | verification resource | user-token verification | verification where needed |

Outside local, missing/blank secrets disable issuance/acquisition safely. The local
profile uses a publicly known DEV-only credential, unsafe for production. `.env.example`
uses a placeholder. Supply a high-entropy deployment secret separately to Auth and
Quote using a secret manager or secret mount/config integration. Auth compares UTF-8
credential bytes with `MessageDigest.isEqual`; it does not store a second database
copy. Configuration is plaintext in process memory, not a persisted hash. Use TLS for
non-local credential exchanges and protect deployment configuration and diagnostics.
Do not enable HTTP header/body tracing that exposes Basic or Bearer credentials.
Pricing never receives Quote's secret; only Auth owns the private key. Tokens and
secrets are never logged by this implementation. Production hardening includes token
endpoint brute-force protection, credential rotation, and potentially separate
issuers/audiences/signing keys. JWKS/rotation and mTLS remain deferred.

## Cache, failure and transaction behavior

Quote keeps each instance's service token only in memory. A synchronized acquisition
method allows one token request at a time; concurrent callers reuse the result.
Expiry uses request start plus `expires_in`, avoiding latency extending validity.
Tokens become stale 30 seconds early; the margin is capped at half the lifetime for
short disposable TTLs. Auth HTTP uses existing Pricing connect/read timeout settings.
No distributed cache, files, database, frontend or Kafka token transport is used.

On Pricing 401, Quote conditionally invalidates the rejected token (preserving a
concurrently replaced cache), obtains another token and retries calculation once.
A second 401 follows the existing safe 502 pricing error path. 403 is not retried.
Token acquisition failures return safe 503. Existing Pricing timeout/business/invalid
response handling remains intact. Quote still starts persistence only after usable
pricing, so failures create no aggregate, QuotePriced intent or partial state.
The same bounded customer correlation ID is captured once and forwarded to Auth,
Pricing and its retry. The caller's user JWT is never used as the machine credential.

## Authorization separation

| Credential | Quote customer API | Pricing internal calculate | Pricing admin |
| --- | --- | --- | --- |
| CUSTOMER user JWT | allowed | 403 | 403 |
| ADMIN user JWT | existing read permissions | 403 | allowed |
| quote-service JWT | rejected by user-only decoder | allowed with required claims | 403 |
| absent/invalid/expired JWT | 401 | 401 | 401 |

Pricing independently pins RS256 and validates public-key signature, issuer, expiry,
required expiration and subject. Internal authorization additionally requires service
use, exact subject, array scope and target audience. Wrong service/scope/audience is
403. Service tokens receive no role authorities, even if they contain a roles claim.
User tokens still require nonempty roles and existing ADMIN rules. HS256, none,
RS512, malformed and wrong-key JWTs remain rejected; there is no symmetric fallback
or per-request Auth introspection. User identities cannot replace service identity,
and service identity cannot impersonate CUSTOMER/ADMIN.

See [validation](../phase-25-validation.md) for executed results and file manifest.
