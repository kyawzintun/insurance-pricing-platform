# Phase 24 — Asymmetric JWT

Access JWTs use **RS256 only**, with one configured RSA pair. Auth owns the private
signing key and public verification key. Gateway, Quote, Pricing and Audit receive
only the public key and independently validate each bearer token. Auth's admin
endpoints also verify with the public key through a separate decoder. Notification
exposes health/info and denies other HTTP requests; it has no JWT decoder.

```text
               PRIVATE KEY
                   |
                   v
             Auth Service
                   |
              RS256 JWT
                   |
        +----------+----------+
        |          |          |
     Gateway     Quote      Pricing
        |          |          |
     PUBLIC      PUBLIC     PUBLIC
        |
      Audit
      PUBLIC
```

## Configuration and formats

| Property | Environment | Required by |
| --- | --- | --- |
| `auth.jwt.private-key` | `AUTH_JWT_PRIVATE_KEY` | Auth only |
| `auth.jwt.public-key` | `AUTH_JWT_PUBLIC_KEY` | Auth, Gateway, Quote, Pricing, Audit |
| `auth.jwt.issuer` | `AUTH_JWT_ISSUER` | All five; default `insurance-auth-service` |
| `auth.jwt.access-token-ttl` | `AUTH_JWT_ACCESS_TOKEN_TTL` | Auth; default `15m` |

Key properties are Spring resource locations, such as
`file:/run/secrets/jwt-private-key.pem` and `file:/etc/insurance/jwt-public-key.pem`.
They are locations, not inline PEM or Base64 symmetric secrets. Private PEM must be
unencrypted PKCS#8 (`BEGIN PRIVATE KEY`), and public PEM must be X.509
SubjectPublicKeyInfo (`BEGIN PUBLIC KEY`). Keys must be RSA with at least 2048 bits.
Protect private files using deployment secret mounts and filesystem permissions.

Production must explicitly select a non-local profile, e.g.
`SPRING_PROFILES_ACTIVE=production`, and supply external key locations. As established
in this learning project, the default profile remains `local`. Local YAML explicitly
uses **publicly known development-only** PEM fixtures in `resources/keys`. Never use
these fixtures for real users or production. The Auth application contains the local
private fixture; verifier application jars contain public fixtures only. All modules'
test private fixtures live exclusively under `src/test/resources` and are not packaged.
Tests use a deterministic checked-in pair, independent of developer file paths.

Generate deployment keys outside the repository, without printing them:

```sh
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private-key.pem
openssl pkey -in jwt-private-key.pem -pubout -out jwt-public-key.pem
```

Mount the private file only in Auth. Mount the public file in each verifier.
A missing, invalid, non-RSA or undersized configured key prevents startup. Auth also
checks matching moduli and performs a signing/verification probe to reject mismatched
pairs before serving requests. Errors describe configuration requirements without
including rejected key contents. Never paste deployment keys into logs or documentation.

## Signing and verification

`JwtService` explicitly sets RS256, and the existing login and refresh paths share
that issuer. Nimbus signs using Auth's RSA private key. Claims remain exactly `sub`
(user UUID), `roles`, `iat`, `exp` and `iss`; roles, issuer and TTL semantics are
unchanged. Each service's focused `RsaKeys` helper reads its public resource, and
its existing decoder explicitly pins `SignatureAlgorithm.RS256`. The decoder never
needs a private resource. No HTTP discovery or dependency on a running Auth service
is added for validation.

Existing required-exp, issuer, zero-skew expiration, subject and role validators
remain in place. Quote retains its canonical UUID subject validation; other services
retain their existing subject and actor handling. Existing safe 401/403 responses,
CUSTOMER ownership, ADMIN permissions, correlation forwarding and event actor derivation
remain unchanged. There are no trusted identity headers replacing bearer validation.

HS256 (including an HMAC made with public-key bytes), `alg=none`, wrong RSA signatures
and unsupported algorithms are rejected. There is no symmetric or dual-algorithm
fallback. Public keys can be distributed, but cannot issue valid access tokens.

## Refresh and deployment

Refresh tokens remain opaque random values in HttpOnly cookies, stored hash-only,
rotated and revocable exactly as in Phase 23. No schema, migration, event contract,
outbox, retry/DLQ, business API or frontend production behavior changes.

Previously issued HS256 access JWTs stop working after rollout. Existing otherwise
valid refresh sessions can obtain new RS256 access JWTs; users may need refresh or
login. Coordinate public-key deployment to all five services. Only one key is active;
changing it invalidates existing access tokens. No zero-downtime rollover is promised.

No JWKS endpoint, remote key fetch, `kid`, multiple signing keys, automatic rotation,
OAuth2 server, blacklist or JWE is implemented. Service-to-service authentication is
Phase 25 and is not implemented here; the existing internal Pricing calculation path
retains its prior behavior.

## Validation

See [Phase 24 validation](../phase-24-validation.md) for executed commands, results,
file manifest and limitations. The disposable `infrastructure/validation/phase24-rs256-e2e.py`
harness uses ephemeral external PEM files, PostgreSQL, Kafka and all five real service
processes. It passes the private-key property only to Auth, checks direct and Gateway
access, verifies login/refresh signatures, rejects forged algorithms/signatures, and
checks verifier jars and logs without printing private material.
