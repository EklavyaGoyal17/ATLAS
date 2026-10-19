# Testing strategy

Never substitute H2 for PostgreSQL when testing advisory locks, row locks, constraints, or Flyway migrations. `PaymentIntegrationTest` starts the application on a random port against an explicitly configured disposable PostgreSQL database. Maven fails rather than skipping integration tests when it is missing. Tests are not wrapped in a single test transaction: independent HTTP requests must commit and race through separate database connections.

The dedicated test database must be named `atlas_test`; test setup truncates its business tables only. Flyway initializes it first. CI provisions PostgreSQL 17. Tests verify migration presence, exact response replay after later mutations, concurrency, conflicting payloads, constraints, transaction rollback, authentication, API validation, per-currency reconciliation and persistence across a second application context. A fixed-seed generative loop exercises partial refunds and invariants.

Frontend: TypeScript compilation and Vite production build. Playwright drives the real console against the actual API: connect, create, replay, capture, refund, timeline and ledger. No mocked backend or invented dashboard numbers.

## Verification record

### Continuation — isolated logical backup and restore

`node scripts/restore-drill.mjs` passed locally on 19 October 2026 against PostgreSQL 17.11. Four migrations and generated payment/ledger, replay, leased outbox, consumer/webhook receipt, worker-clock and journal/revision fixtures were dumped and restored in a new network-isolated tmpfs container. Restored rows matched; ledger totals balanced; unique capture rejection, identity-sequence advancement and revision deletion cascades passed. Cleanup verified the container's per-run labels. No existing application/test database or volume was accessed. This additional drill is separate from the 88 backend test cases and does not validate actual application backups or production disaster recovery. See RECOVERY.

### Current full workspace verification — 19 October 2026

Executed against Docker Engine 29.5.3, PostgreSQL 17.11, the real nginx console, Node 24.13.1, native Java 25.0.3 and the separate Temurin Java 21 Linux test runner. The complete verification entry point passed. Focused checks were repeated after the final Git trust-boundary and frontend refinements. No hosted CI result or live model result is implied.

| Executed check | Result |
|---|---|
| `powershell -NoProfile -File scripts/verify.ps1` | PASS: native suite, Java 21 container suite, locked install/build/audit, Compose health, browser workflows, real API restart and read-endpoint measurements. Git evidence was also run separately and is now included in the entry point. |
| Native Maven verification | 88 cases: 87 passed, 1 host-restricted symlink case skipped; zero failures/errors. |
| Java 21 Linux `mvn -B verify` | All 88 cases passed; zero skips/failures/errors, including symlink controls. |
| Generated invariants | RefundPropertiesTest runs 5000 seeded refund sequences; twelve simulation recipes each check 100 deterministic seeded inputs, bounds, timeline ordering and their stated final invariant. These loops are within the test-case counts above. |
| Real Compose browser suite | All 10 Chromium workflows passed, including payments/worker recovery, approved source navigation, context preview, incident/postmortem, diagram versions/SVG/reload, phone journal/rubric, saga model and signed duplicate HTTP delivery. No API mocks. |
| API/JVM restart | PASS: one committed effect survives, recovery delivers without a second effect, payment state stays authorized; engineering note persists and repository memory snapshot is cleared. |
| Actual Git fixture | PASS: a synthetic committed repository under ignored .runtime is approved through the API; bounded Git inspection returns its commit subject and tracked diff. Import returns to ATLAS afterward. No user repository commit was made. |
| Frontend production build / npm audit | PASS; zero reported frontend vulnerabilities. The client-only dependency directive warning is harmless. |
| Final source/API-image/console-image Trivy scans | Zero reported High/Critical findings, with exit-code gates. App images include the new OTel/JavaParser dependencies and runtime Git. This is a point-in-time selected-severity check, not a security certification. |
| API documentation | 36 operations and 156 internal references checked with `python scripts/check-contract.py` (requires PyYAML). This is a reference/operation check, not full OpenAPI conformance validation. |
| Visual/responsive inspection | Desktop and 390px screenshots, no page exceptions, no CSP style violations and no document-width overflow in source/design/growth views. Screenshot inspection is not a full assistive-technology/WCAG audit. |

The complete log and raw scan outputs are in ignored .runtime. Actual screenshots are copied to docs/images. Intermediate runs caught a migration-count assumption, numeric JSON-node comparison in the webhook receiver, a minimal preview DTO mismatch, unresolved call receivers and overly strict label selectors. Each was corrected and the relevant checks rerun; failed attempts are not counted as passing runs.

#### All seventeen originally required test categories

| Requirement | Executed evidence |
|---|---|
| Duplicate idempotency keys | PaymentIntegrationTest simultaneous requests, changed payload and immutable response replay; load.mjs |
| Concurrent state updates | Capture/refund races in PaymentIntegrationTest; document compare-and-set race in EngineeringIntegrationTest |
| Invalid transitions | PaymentRulesTest and HTTP integration conflicts with unchanged state |
| Ledger balancing | Paired posting checks, reconciliation and intentionally inconsistent amount/currency fixtures |
| Partial/repeated refunds | PaymentIntegrationTest and 5000 generated refund sequences |
| Worker interruption/restart | DeliveryIntegrationTest both commit windows and recovery-check.mjs real container restart |
| Duplicate event delivery | Inbox tests, redelivery browser flow and three real signed HTTP webhook receipts |
| Retry exhaustion | DeliveryIntegrationTest backoff/dead letter/redelivery and browser scenario |
| Migration correctness | Isolated schema V1-to-V2-to-workspace/webhook upgrade; legacy rows retained without invented historical envelopes |
| API validation | Payment/engineering HTTP tests, document/diagram bounds and minimal preview contract |
| Authorization failures | Payment and new module unauthenticated requests return 401; webhook signature rejection |
| Path traversal | Relative/absolute/drive/backslash escapes and unindexed file rejection |
| Secret redaction | Credential/private-key fixtures; redacted bridge question transport |
| Provider outages | AssistantService unavailable/invalid-citation fallback and typed local bridge contract tests; no live model used |
| Prompt injection | Instruction-like fixture excluded from retrieval; repository JavaScript remains unexecuted; no command/file tools |
| Core frontend workflows | Ten real-Compose Chromium flows, including desktop/phone and persisted artifacts |
| Application restart recovery | Fresh contexts plus actual API container/JVM restart, durable effect/note and cleared repository snapshot |

#### Small local load observations

`node scripts/workspace-load.mjs`: concurrency 4, 20 samples per endpoint, warmed local Docker API/PostgreSQL, no TLS or WAN. Every response was 200.

| Endpoint | p50 | p95 | Maximum |
|---|---:|---:|---:|
| Payment history, size 20 | 15.94 ms | 40.88 ms | 71.89 ms |
| Engineering records, page 0 | 12.97 ms | 20.54 ms | 20.60 ms |
| Local telemetry summary | 7.33 ms | 12.08 ms | 12.28 ms |

Latest duplicate burst: 25 concurrent same-key submissions, one 201, 24 replay responses with 200, one distinct payment, 138.34 ms wall time, observed HTTP durations 60.53–101.92 ms. These small observations are not capacity evidence or a production SLO and are not controlled comparisons with the historical runs below.

Not executed: hosted GitHub Actions (no remote), a live API/local-model answer, external source transmission, production/shared deployment, actual application-pool exhaustion, broker failover, OS power loss, database restore or independent manual screen-reader/penetration review. Optional live-model quality is separate from the tested provider contract and fallback. Historical sections below describe earlier checkpoints only.


### Earlier continuation — Compose and durable delivery verified

Executed 19 October 2026 (Asia/Calcutta) after Docker Desktop became available: Engine 29.5.3 / Desktop 4.79.0, PostgreSQL 17.11 containers, Node 24.13.1, Windows host Java 25.0.3 and container Temurin Java 21. Final patches: Tomcat 11.0.27, Jackson BOM 3.1.8, nginx 1.30.6-alpine and Alpine package updates.

| Check actually run | Result |
|---|---|
| `powershell -NoProfile -File scripts/verify.ps1` | PASS: complete install/build/test/Compose/browser/restart sequence using the public local example configuration. |
| Host `mvn -B -f backend/pom.xml verify` | PASS: 46 tests, zero failures/errors/skips; packaged JAR. 17 unit cases plus 29 PostgreSQL integration/upgrade cases. |
| Separate Java 21 container `mvn -B verify` | PASS: the same 46 tests against disposable PostgreSQL. |
| `npm ci`, `npm run build`, `npm audit --audit-level=high` | PASS: locked install, TypeScript/Vite build, zero reported npm vulnerabilities. Client-only dependency directive warnings remain. |
| Compose image builds and `up --build -d --wait` | PASS: actual API, nginx console, application database and separate disposable test database; API/database health checks pass. |
| `ATLAS_E2E_EXTERNAL=true npm run test:e2e` | PASS: 5 Chromium workflows against actual containers, including payment lifecycle/replay/ledger, authentication, both delivery interruption windows, duplicate redelivery, dead letters and phone layout. |
| `node scripts/recovery-check.mjs` | PASS: actual API container/JVM restart preserves one consumer effect; recovery suppresses one duplicate and leaves payment AUTHORIZED. |
| Trivy 0.76.0 source dependency graph | PASS: zero High/Critical findings after initial managed-library findings were remediated. |
| Trivy scans of exported final API and console images | PASS: zero High/Critical findings in both runtime images, including OS packages and packaged Java dependencies where detectable. |
| `node scripts/load.mjs` against Compose | PASS: 25 concurrent duplicate requests, one 201, 24 responses with 200, one payment ID. 284.99 ms total; observed HTTP times 144.60–283.28 ms. |
| OpenAPI YAML/reference check | PASS: document parses and all 81 internal references resolve. Not a full OpenAPI conformance validator. |

The Java 21 suite used `docker build --target build -t atlas-test-runner backend`, then `docker run --rm --network container:atlas-test-db-1 -e ATLAS_TEST_DB_URL=jdbc:postgresql://localhost:5432/atlas_test atlas-test-runner mvn -B verify`. Sharing the disposable database container's network namespace preserves the localhost/name guard. Host and container tests execute sequentially against atlas_test; browser tests use the separate atlas database.

Delivery tests prove atomic publication, transaction-required publishing, pre-commit rollback, post-commit recovery, lease expiry, stale apply/ack/failure fencing, bounded retries, exhausted/abandoned attempts, redelivery, experiment-key replay/conflicts, actual scheduling, API validation and fresh-application recovery. A migration test upgrades an isolated V1 schema to V2 without fabricating historical event payloads. Fixtures are created/deleted only in guarded atlas_test.

Intermediate checks exposed an assertion-overload compilation issue and dependency/package vulnerabilities. These were corrected before the final full regression, Java 21, browser and image scans. Evidence lives in ignored `.runtime/`: `full-project-verification.log`, `final-java21-tests.log`, `trivy-source-patched.json`, `trivy-api-final.json`, `trivy-console-final.json`, `compose-recovery.log`, and `compose-load.json`. Desktop and 390px phone screenshots were visually inspected. See DEPENDENCY_REVIEW for scanner scope.

Not yet verified: GitHub workflow execution, full accessibility/browser matrix, database power-loss recovery, backup restore, load capacity, external brokers/webhooks, or the unimplemented repository/AI/incident modules. Scans are point-in-time reports at selected severities, not a compliance or vulnerability-free claim.

### Earlier native-only checkpoint (superseded by the results above)

Executed on 19 October 2026 (Asia/Calcutta), Windows 11, Intel i7-12650H, Java 25.0.3 (source/bytecode target 21), Maven 3.9.16, Node 24.13.1, PostgreSQL 17.11 from the checksum-verified Windows fallback. The host's Docker Desktop engine failed during startup; no containerized build or startup success is claimed.

| Check actually run | Result |
|---|---|
| `mvn -B -f backend/pom.xml verify` | PASS: 30 tests, zero failures/errors/skips; executable JAR produced. 16 unit cases + 14 PostgreSQL integration tests. |
| `npm run build` in frontend | PASS: strict TypeScript + Vite production bundle. Vite reports dependency `use client` directives in this client-only app. |
| `npm run test:e2e` | PASS: 2 Chromium flows against the actual API; desktop lifecycle, replay, capture/refund, persisted reload, invalid token, reversal, keyboard palette and phone layout. |
| `npm audit --json` | Zero reported vulnerabilities in the frontend dependency set at execution time. Not a backend/container audit. |
| `docker compose --env-file .env.example config --quiet` | PASS: configuration validates. Engine unavailable, so image build/startup is unverified. |
| `powershell -NoProfile -File scripts/local-postgres.ps1 start` | PASS: starts/reuses the workspace-local PostgreSQL instance and creates fixed local databases. |
| `node scripts/seed.mjs` | PASS: five synthetic scenario workflows created; stable keys make reruns safe. |
| OpenAPI YAML/reference check | PASS: document parses and all 50 internal references resolve. This is not a full OpenAPI conformance validation. |
| `node scripts/load.mjs` | PASS: 25 concurrent duplicate requests, one 201, 24 responses with 200, one distinct payment ID. |

The local duplicate burst completed in 109.25 ms wall time, with observed HTTP durations of 43.59–76.13 ms. This is one short burst on an already-running local API/database, without TLS or WAN traffic. It measures duplicate contention, not representative throughput or a service-level objective. Run the script yourself; results are machine/workload dependent.

Build/test logs, screenshots and raw audit output from this session are in ignored `.runtime/`; the durable summary is this document. Initial verification failed while the database was unavailable and then exposed a repository-proxy field-access bug and fresh-context configuration error. Both were corrected before the passing runs. An intermediate JAR packaging attempt also failed due to a running preview holding the file open on Windows; the verified build passed after stopping that preview. No failed attempt is counted as a passing result.

Browser screenshots were inspected at 1440px and 390px widths. The phone review exposed an absolutely positioned table header extending the document width; positioning the scroll container fixed it. These checks do not constitute a full WCAG audit.

Not executed: Java 21 runtime tests on this host (CI is configured for 21), GitHub Actions, container vulnerability scan, production deployment, database OS-crash recovery, backup restore, real worker/webhook delivery, repository import, redaction, provider outages or prompt-injection tests. Unimplemented features do not have passing tests.

## Performance

Initial target: a 25-request duplicate burst produces one payment and one authorization event. This is a correctness experiment, not a throughput benchmark. `scripts/load.mjs` reports real wall time and status counts for a bounded run against a running local API. Record host, runtime, database, request count and limitations with any result. No percentile or SLO claim until sufficient observations exist.

The latest Compose burst ran after application warmup on the same i7-12650H host, with local Docker networking and no TLS/WAN traffic. It is not a controlled comparison with the earlier native burst: code, deployment, caches and host workload differed.
