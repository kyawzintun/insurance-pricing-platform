# Auth Service — Authentication Basics

Phase 4 implements customer registration and login using the existing `auth_db.users` table. Flyway migrations are unchanged. There is no refresh-token, logout, token rotation, Kafka, outbox-publishing, or gateway-routing implementation.

## Run Locally

JDK 25 and the root Maven Wrapper remain the build baseline. From the repository root:

```bash
docker compose up -d
./mvnw -pl services/auth-service spring-boot:run
```

Auth defaults to the `local` profile, PostgreSQL at `localhost:15432`, and a public development-only signing key in `application-local.yml`. No exports, launcher, or generated secret file are needed. PostgreSQL must already be running; stop Auth with Ctrl+C.

From `services/auth-service/`, the equivalent command is `../../mvnw spring-boot:run`. Environment variables still override the YAML defaults, but Maven does not load `.env` automatically. If your database port differs, update the local YAML or export `POSTGRES_PORT`. Explicitly selecting another profile disables the default local profile and requires appropriate datasource/key configuration. Never reuse the committed development key outside this learning project.

| Variable | Meaning / default |
| --- | --- |
| `AUTH_JWT_SECRET` | Optional local override of the public development key; required outside the local profile. Must encode at least 32 bytes. Invalid keys prevent startup. |
| `AUTH_JWT_ACCESS_TOKEN_TTL` | Access-token lifetime; default `15m`. Must resolve to a positive whole number of seconds. |
| `AUTH_JWT_ISSUER` | Issuer string; default `insurance-auth-service`. |
| `AUTH_SERVICE_PORT` | Default `8081`; standard `SERVER_PORT` can also override it. |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Optional overrides; local defaults are localhost:15432, insurance_dev / local_dev_only; database remains `auth_db`. |

## Public Endpoints

| Method / path | Success | Behavior |
| --- | --- | --- |
| `POST /api/v1/auth/register` | 201 | Creates an ACTIVE CUSTOMER; returns a safe user response. |
| `POST /api/v1/auth/login` | 200 | Returns an access token for valid credentials of an ACTIVE user. |
| `GET /actuator/health` | 200 when healthy | Includes database readiness, with component details hidden. |
| `GET /actuator/info` | 200 | Minimal Actuator information. |

Registration request (replace the example password before use):

```json
{
  "email": "customer@example.com",
  "password": "replace-with-your-own-password",
  "firstName": "Kyaw",
  "lastName": "Tun"
}
```

The 201 response contains `id`, canonical `email`, trimmed `firstName`/`lastName`, `role`, `status`, and `createdAt`. It never contains a password or hash. Public registration always assigns `CUSTOMER` and `ACTIVE`; unknown fields, including `role` and `status`, are rejected with 400.

Login request:

```json
{
  "email": "customer@example.com",
  "password": "replace-with-your-own-password"
}
```

A successful response contains `accessToken`, `tokenType` (`Bearer`), and `expiresIn` (seconds; 900 by default). Responses containing tokens use `Cache-Control: no-store`. No refresh token is issued.

### Validation and Errors

Emails are stripped of surrounding whitespace and lowercased with `Locale.ROOT` before validation, storage, and login lookup. Repository lookups also ignore case. The existing unique email constraint remains unchanged; canonical writes and the unique constraint protect concurrent duplicate registrations. Direct database writes must preserve canonical emails; no database collation/schema change was introduced.

Names are required, stripped, and limited to 100 characters. Emails are required, format-checked, and limited to 255 characters. Registration passwords require at least 12 characters and at most 72 UTF-8 bytes, matching BCrypt's input limit; no forced symbol/uppercase rules. Passwords are never trimmed or silently truncated. Login requires a nonblank password within the same byte limit.

Errors use only `code` and `message`:

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Missing/invalid fields, unknown fields, malformed JSON, or unsupported content types. |
| 401 | `INVALID_CREDENTIALS` | Unknown email, incorrect password, or DISABLED user; identical message: `Invalid credentials`. |
| 401 | `UNAUTHORIZED` | Request to a protected endpoint without accepted authentication. |
| 403 | `FORBIDDEN` | Access denied by security policy. |
| 409 | `EMAIL_ALREADY_EXISTS` | Canonical email already registered, including concurrent registration conflicts. |
| 500 | `INTERNAL_ERROR` | Unexpected failure; no exception details exposed. |

Registration intentionally reports duplicate email as requested. Login uses a dummy BCrypt hash check for unknown accounts to reduce obvious password-check timing differences. There is no production rate-limiting or account-lockout system in this learning phase.

## Passwords and JWTs

Spring Security `BCryptPasswordEncoder` uses cost **12**, with a fresh salt per password. Passwords, hashes, tokens, and authorization headers are never logged by Auth code. Request/token DTO string representations redact their contents; validation errors do not echo rejected values. User entities are never serialized as API responses.

JWT generation uses Spring Security's Boot-managed `spring-security-oauth2-jose` / Nimbus implementation, with **HS256**. Claims are limited to `sub` (user UUID), `roles` (CUSTOMER or ADMIN), `iat`, `exp`, and `iss`. No password, name, or email is embedded. The lifetime is configurable and defaults to 15 minutes. Changing the signing key changes which key a future validator needs.

The API is stateless, uses no login session/cookie, and disables form login, HTTP Basic, CSRF, and logout handlers. Only the four method/path combinations listed above are public. Other requests require authentication. This phase issues tokens but does not install a bearer-token validation filter or distributed JWT validation; no protected business APIs exist yet. Gateway routing and JWT validation belong to Phase 5. Disabling a user prevents subsequent login, but revoking already-issued access tokens is outside this phase.

## Explicit Local ADMIN Creation

There is no public admin-registration endpoint or automatic admin initializer. For a development-only administrator:

1. Register a dedicated local account such as `local-admin@example.com` through the normal registration endpoint with a password you choose. It initially becomes an ACTIVE CUSTOMER, with a BCrypt hash.
2. Using your local database administrator access, explicitly promote that exact dedicated account:

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

Verify that exactly the intended account was returned. This step creates no additional users, does not reset passwords, and is harmless to repeat (an existing ADMIN is not updated). Login again to obtain a token with the ADMIN role. No real password is placed in migrations or documentation. This manual operation is for the local learning database, not a production provisioning workflow.

## Validation

```bash
./mvnw -pl services/auth-service -am clean verify
./mvnw clean verify
```

Auth tests run a real HTTP server and security/validation stack with a mocked repository, real BCrypt, and an ephemeral generated signing key; they need neither PostgreSQL nor Kafka. JWT tests verify signatures, subject/roles, configurable expiry, tamper rejection, invalid key configuration, and DTO redaction. Live PostgreSQL validation separately checks persistence, duplicate races, existing Flyway history, and disabled/admin behavior. See [Phase 4 validation](../../docs/phase-4-validation.md).

Library references: [Spring Security password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) and [NimbusJwtEncoder](https://docs.spring.io/spring-security/site/docs/7.0.0/api/org/springframework/security/oauth2/jwt/NimbusJwtEncoder.html).

## Package Structure

Auth Service uses conventional layers under `com.insurance.platform.auth`:

```text
controller/   AuthController — HTTP endpoints
service/      AuthService and JwtService — authentication and token generation
repository/   UserRepository — database access
entity/       User — JPA table mapping
dto/          Request/response objects and ApiError
exception/    AuthException and ApiExceptionHandler
enums/        Role and UserStatus
config/       Spring Security and JWT configuration
```

`AuthServiceApplication` stays in the root package so component, entity, and repository discovery cover these subpackages. Request flow is controller → service → repository. Responses use DTOs, never the entity. This organization does not change URLs, database mappings, or authentication behavior.
