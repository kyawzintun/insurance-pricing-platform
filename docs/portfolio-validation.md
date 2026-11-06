# Final Portfolio Polish — Validation and Completion

Validated 2026-11-06 (Asia/Bangkok). Documentation/presentation/repository review only: no product features, new technical phase, application-code changes, schema changes or dependency upgrades.

## Final Validation

| Check | Result |
| --- | --- |
| `./mvnw -B verify` | PASS: 456 tests, zero failures/errors/skips; all six packaged services |
| Integration-only `postgres-it` command in testing guide | PASS: 57 tests, real PostgreSQL/Flyway |
| Integration-only `kafka-it` command in testing guide | PASS: 8 tests, real broker/database |
| `npm ci` | PASS with temporary cache override; lockfile unchanged |
| `npm test` after clean install | PASS: 130 tests in 13 files |
| `npm run build` after clean install | PASS: production output; initial bundle 293.71 kB, estimated transfer 82.82 kB |
| PostgreSQL performance harness | PASS: identical results and all four structural index assertions; separate `/tmp/portfolio-performance.json` |
| Packaged Operations E2E harness | PASS: six services, real HTTP login/refresh, role boundaries, outbox/consumers, Pricing outage/recovery, circuit telemetry and signing-key isolation |
| `docker compose config --quiet` | PASS; infrastructure-only configuration |
| `./mvnw --version` | Maven 3.9.16 / JDK 25.0.1 |
| Frontend startup | PASS: `npm start -- --port 4201`, successful HTTP response, then stopped |
| Local Markdown file/fragment links | PASS after final review |
| `git diff --check` | PASS |

Integration-only commands (normal tests were run separately):

```bash
./mvnw -B -Ppostgres-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false verify
./mvnw -B -Pkafka-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false verify
python3 infrastructure/validation/phase27-postgres-performance.py --output /tmp/portfolio-performance.json
python3 infrastructure/validation/phase27-operations-e2e.py
```

Individual Surefire reports sum to 456. PostgreSQL module results are Auth 9, Quote 20, Pricing 4, Notification 11, Audit 13. Kafka module results are Auth 1, Quote 1, Pricing 1, Notification 2, Audit 3. Historical report counts were not overwritten.

### Environment Findings

The sandboxed Angular bundle process aborted; a permitted rerun outside the sandbox passed, including after clean installation. Initial npm installation could not write the global cache; an unrestricted retry identified existing root-owned cache files. `npm ci --cache /tmp/insurance-portfolio-npm-cache` passed without changing global cache ownership. The restricted shell reported Node 24.21.0 / npm 11.19.0; the unrestricted npm install reported npm 11.12.1. The repository still declares npm 11.19.0; no toolchain files changed.

Default port 4200 was already occupied. The existing process was left alone; a temporary 4201 dev server returned the application HTML and was stopped. Alternate-origin authenticated browser calls would require a corresponding CORS override; this was a dev-server startup check. Six-service HTTP validation used the isolated E2E harness rather than resetting shared development databases. The individual Maven run commands were checked against modules/configuration; the E2E starts packaged jars, not six simultaneous `spring-boot:run` commands. Clone URL was checked against the configured Git remote; no second clone or destructive database-reset instructions were executed.

`npm audit` reported **six existing affected dependency entries: 1 low, 1 moderate, 4 high**, zero critical. Entries are Angular build/CLI, fast-uri, undici, Vite and zod. Some entries inherit severity through their dependency chain. This phase did not apply `npm audit fix` or upgrade the pinned toolchain. These findings are a known dependency limitation; completion is not a claim of a clean vulnerability audit or production readiness. Raw audit data remains outside the repository at `/tmp/portfolio-npm-audit.json`.

## PostgreSQL Rerun

Fresh local observations, milliseconds (not benchmark distributions or service SLAs):

| Query | Before | After | Structural result |
| --- | ---: | ---: | --- |
| Audit correlation | 14.637 | 0.159 | Sequential scan → bitmap index/heap lookup |
| Global quote first ID page | 12.881 | 0.111 | Scan/sort → ordered index-only scan |
| Auth email IgnoreCase | 5.605 | 0.078 | Scan → expression index |
| Auth customer page | 4.680 | 0.092 | Scan/sort → ordered index |

The harness also checks unchanged paths and identical ordered results. Some unchanged queries were slower in this single rerun, reinforcing the plan/buffer-based interpretation rather than a blanket speed claim. Original [Phase 27 evidence](phase-27-postgres-performance-evidence.json), [SQL capture](phase-27-postgres-sql-capture.txt) and [methodology](phase-27-postgres-performance-validation.md) remain intact.

## Repository and Secret Review

The starting working tree was clean. Review covered the tracked file inventory, README/guides, phase specifications/reports, event contracts, Maven/npm manifests, migrations, local configuration, frontend routes/state, security/consumer/publisher code and validation harnesses. The source/configuration takes precedence over historical prompts.

Tracked-content scanning covered password, access/refresh-token, client-secret, Authorization and private-key references, JWT-shaped literals and common GitHub/AWS credential patterns. No real credential, JWT literal or unclassified private key was found. Password/header references resolve to implementation, placeholders, synthetic tests or explicitly public local credentials. `.env` and `.env.auth-secret` are ignored/untracked and their contents were not copied into documentation. This is a current tracked-content review, not a Git-history scan or proof that all possible secrets can be detected.

Six private PEM fixtures are intentionally tracked: Auth main local-development key plus test fixtures for Auth/Gateway/Quote/Pricing/Audit. Their key-directory READMEs explicitly label them DEVELOPMENT / TEST ONLY; verifier jars contain no private signing material. Local PostgreSQL/service credentials and plaintext Kafka are labeled DEV-only. Production key paths use placeholders; no real private material was added.

No tracked `__pycache__`, `.pyc`, runtime logs, target/node_modules files or IDE junk was found. Useful validation evidence and historical phase reports remain. Existing `.gitignore` already covers generated files, environment files, private key formats and Python artifacts, with narrow fixture exceptions; no ignore change or deletion was needed. Validation logs/cache/rerun JSON stayed in temporary directories or existing ignored build paths.

No classic `@ngrx/store`/`@ngrx/effects` dependency/import remains in frontend source, manifest or lockfile. No production HS256 implementation or JWT-secret configuration remains. No clearly obsolete dependency/code was found that warranted removal; no dependencies or application files changed. There is no configured lint/backend formatter; `git diff --check` and targeted documentation review were used rather than introducing formatting tooling.

## Deliverable Inventory

Created:

- `docs/local-development.md`: startup, environment groups, DEV-only admin SQL and demonstration steps.
- `docs/testing.md`: test boundaries, exact profile commands, disposable harnesses and prerequisites.
- `docs/interview-guide.md`: 15 short explanations and concrete project examples.
- `docs/engineering-tradeoffs.md`: rationale, consequences, production boundaries and optional ideas.
- `docs/screenshots/README.md`: real-application capture checklist, with no fabricated screenshots.
- `docs/portfolio-validation.md`: this report.

Modified substantive guides: root `README.md`; `docs/architecture.md`, `security.md`, `database-design.md`, `kafka-design.md`, `implementation-roadmap.md`, `project-context.md`, `functional-requirements.md`, `non-functional-requirements.md`, `domain-model.md`, `specs/README.md`; `frontend/README.md`; `infrastructure/README.md`; `.env.example`; and the infrastructure-only comment in `docker-compose.yml`.

Added current/historical scope notices to service READMEs, phase specifications and validation reports, plus event/outbox/retry/customer-management guides. Admin-customer guide's current JWT reference now says RS256. Original historical report bodies remain unchanged; historical HS256/direct-publisher/old NgRx statements are clearly scoped, not rewritten to pretend they never existed. No files removed.

## Requested Completion Review

| Deliverable | Final state |
| --- | --- |
| Root README / purpose | Portfolio landing page: overview, motivation, actual workflows and complete status |
| README structure | Architecture/service/stack tables, decisions, reliability, security, database/performance, tests/scenarios, tree, setup, APIs/events, screenshots, future ideas and reusable CV summary |
| Architecture | Current ownership diagram and Create Quote, Login/Refresh, Admin Pricing sequence diagrams |
| Security / reliability | RS256, machine scope/audience, rotation, roles/ownership, CORS, safe logs, outbox/idempotency/retry/DLQ and token-revocation limits |
| Database / performance | Current schema inventory and measured plan improvements linked to preserved evidence |
| Setup / running | Wrapper commands, infrastructure-only Compose, npm scripts, environment groups, cookie topology and DEV-only provisioning |
| Testing | Current commands, layered strategy, real DB/broker profiles, disposable E2E and performance method |
| API / events | All implemented public/admin/internal route families and v1 event inventory |
| Engineering scenarios | Concurrency conflicts, duplicate delivery, outbox crash, outages/circuit and refresh replay |
| Interview material | Fifteen concise speaking outlines with project examples |
| Tradeoffs | Discovery, orchestration, registry, Redis/search, key rotation, tracing, delivery guarantees and local infrastructure |
| Roadmap | All phases complete; optional ideas separated, both Phase 27 records retained |
| Repository hygiene | No tracked generated junk; no deletions/ignore additions necessary |
| Secrets / dependencies | No real tracked secrets found; public fixtures documented; no obsolete dependencies removed; npm advisories recorded |
| Backend / frontend validation | All selected tests, production build, performance and packaged E2E passed |
| Documentation command verification | Wrapper/profile/npm/Compose/performance/E2E checks above; honest startup and environment boundaries stated |
| Remaining documentation gaps | Optional real screenshots are not yet committed; no fresh manual click-through or GitHub Mermaid render was performed in this phase |
| Completion | Portfolio documentation phase and planned implementation COMPLETE; no next implementation phase started |

The project is ready to present as a completed engineering portfolio with explicit limitations. Screenshots are an optional presentation follow-up; dependency advisories and deployment/security boundaries are disclosed rather than hidden.
