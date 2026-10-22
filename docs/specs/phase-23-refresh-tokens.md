# Phase 23 — Refresh Tokens

Implemented using the existing `refresh_tokens` schema; no migration or event changes.
Phase 24 asymmetric JWT is excluded.

```text
Login
  ↓
Access JWT -> Angular memory
Refresh token -> HttpOnly cookie + DB hash

Expired access JWT
  ↓
single refresh request
  ↓
rotate refresh token
  ↓
new access JWT
  ↓
retry queued requests
```

Access JWT retains HS256, issuer, subject, roles, iat and exp. Default lifetime is
15 minutes (`AUTH_JWT_ACCESS_TOKEN_TTL`). Refresh tokens contain 32 SecureRandom
bytes encoded as 43 base64url characters without padding. PostgreSQL stores only
the deterministic SHA-256 digest. Neither session objects nor application code
logs raw credentials; refresh tokens never appear in JSON responses.

POST login preserves credential validation and safe failures, then inserts a token
row and sets a cookie. POST `/api/v1/auth/refresh` reads that cookie, locks its row
with PostgreSQL PESSIMISTIC_WRITE, validates expiry/revocation and current DB user
status, revokes the old row, inserts the replacement and returns the existing access
response contract. Mutation and replacement flush share one transaction: insert
failure rolls back both. Concurrent use has one winner; waiters see the revoked row.
Unknown, malformed, expired, revoked, or disabled sessions return the same safe 401.
A refresh observed while the user is disabled commits revocation before returning
401, so enabling the user does not restore that token. No access blacklist exists.

POST `/api/v1/auth/logout` revokes a matching cookie if present, clears the cookie,
and returns 204 even for missing, malformed, unknown or previously revoked tokens.
Cookie setting and deletion use identical name, path and security attributes.

| Configuration | Default |
| --- | --- |
| `AUTH_REFRESH_TOKEN_TTL` | `7d`, positive whole seconds |
| `AUTH_REFRESH_COOKIE_NAME` | `refresh_token` |
| `AUTH_REFRESH_COOKIE_SECURE` | `true`; local profile `false` for HTTP localhost |
| `AUTH_REFRESH_COOKIE_SAME_SITE` | `Lax`; accepts `Lax` or `Strict` |
| Cookie path | `/api/v1/auth` |
| Cookie HttpOnly | always true |

Use HTTPS and Secure cookies outside localhost. Deploy frontend and Gateway on the
same site. SameSite plus POST-only mutation and Gateway exact-origin credentialed
CORS are this phase's CSRF mitigation. Cross-site frontend deployments are outside
this configuration; wildcard credentialed origins must never be configured. Auth
has no direct cross-origin browser access. Gateway forwards Cookie/Set-Cookie and
correlation headers. Only POST register/login/refresh/logout are public auth routes;
clients omit Authorization there (invalid supplied Bearer tokens still receive 401).
Token responses are no-store; the API creates no servlet session.

Angular keeps access tokens solely in NgRx memory. Login/refresh/logout include
credentials; JavaScript never reads the refresh cookie. A bounded (10 second)
application initializer performs one refresh before initial routed navigation.
Restoration updates state without the login-success dashboard redirect, preserving
deep links and ADMIN/CUSTOMER guard checks. Failure leaves the user signed out.
One shared replayed observable queues concurrent protected 401s. Successful refresh
updates state, then retries each original request with its body, URL and other
headers preserved. A late response uses a newer current token where available.
Retries do not reenter refresh logic. Public auth endpoints and 403s are excluded.
Refresh failure clears state. Logout clears local state immediately even on network
failure and waits for an in-flight rotating response before revoking its cookie;
a generation check prevents that response from restoring local authentication.

Limitations: existing access JWTs remain valid until expiry after disable/logout.
No token-family tracking exists: replay rejects the presented old token, without
revoking all descendants or other sessions. Expired/revoked row cleanup is deferred.
Disabling without an intervening refresh does not revoke every session: after enable,
other still-valid tokens may work. A refresh rejected for disabled status stays revoked.
If logout cannot reach Auth, the HttpOnly cookie cannot be cleared by JavaScript;
local state is cleared but a subsequent reload can restore until server revocation
or expiry. Refresh deduplication is per running Angular app; other tabs can compete
for one cookie and one will receive 401. No session/device UI, token events, outbox
entries, Redis, OAuth, blacklist, JWKS, or asymmetric keys were added.

Validation evidence and exact commands: [Phase 23 validation](../phase-23-validation.md).
