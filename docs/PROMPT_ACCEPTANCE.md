# Master prompt acceptance audit

Audited 22 October 2026 against **PROJECT ATLAS — Build an Engineering Platform Worthy of a Top-Tier Fintech Engineer**. The latest supplied attachment and the original have identical SHA-256 `7d87568d146df536b8775dd6c6c9da03370d74f4bd96df9a3280049fa5655d7f`.

This is a local, single-engineer educational implementation. “Implemented” below means an executable local capability with named verification; it does not mean every possible input, deployment or natural-language answer is proven correct. [TESTING](TESTING.md) records actual executions and failures, [REQUIREMENTS](REQUIREMENTS.md) maps source and scope, and [ROADMAP](ROADMAP.md) separates operating work.

## Phase acceptance

| Phase | Concrete acceptance path | Evidence |
|---|---|---|
| 0: discovery/design | Journeys, domain/transaction/trust boundaries, prioritized phases, risks and decisions | README, ARCHITECTURE, ROADMAP, SECURITY, seventeen DECISIONS and this audit |
| 1: vertical slice | Start React/API/PostgreSQL, authorize synthetic money, inspect history/detail, replay the original intent | Compose, PaymentIntegrationTest, payment browser workflows, DEMO 1 |
| 2: payment correctness | Capture, partial/repeated refunds, reversal, balanced postings, reconciliation and competing commands | PaymentRulesTest, PaymentIntegrationTest, 5000 seeded RefundPropertiesTest sequences |
| 3: distributed concepts | Commit-before/after fault windows, outbox/inbox, fenced leases, bounded retries/dead letters, signed duplicate receipts and twelve deterministic models | DeliveryIntegrationTest, RetryPolicyTest, SimulationEngineTest, real restart and Systems browser flows |
| 4: investigation | Eight controlled scenarios, actual HTTP/JDBC/W3C evidence, separate payment-operation measurements, saved incident evidence and postmortems | Telemetry/TracingFilter/HttpOperations tests, EngineeringIntegrationTest, operation/incident browser flows |
| 5: code intelligence | Approve ATLAS, search source, follow endpoint/transaction/potential-call evidence, inspect qualified architecture/impact and Git | Intelligence, metadata, architecture, impact, import/redaction/security tests; repository browser workflows and Git smoke script |
| 6: engineering assistant | Cited deterministic source facts, conditional investigation/test/review steps, bounded saved-incident summaries; optional provider consent/bridge/fallback | IntelligenceTest, HttpModelProviderTest, IncidentBriefsTest, authenticated saved-summary/reload/deletion/restart and consent workflows |
| 7: engineering workspace | Versioned diagrams and exports; journal/ADR/learning/review records; public concept-to-code links, structured evidence feedback, complete ADR writes, generated application backup/readback | Document/diagram/ADR tests, review/helper/browser flows, both restore drills and injected failure cleanup |
| 8: hardening | Auth/body/rate/import/output bounds, keyboard focus/navigation, responsive layouts, measured load scripts, CI, scans, reproducible demos and setup | Full backend/frontend/browser checks, SECURITY, RECOVERY, DEPENDENCY_REVIEW, DEMO and hosted Actions for the exact implementation commit |

Hosted results apply only to the commit actually checked. The navigation checkpoint `bfea8b3` passed both jobs in [run 39822995107](https://github.com/EklavyaGoyal17/ATLAS/actions/runs/39822995107). The later audit-remediation revision has its own local and publication evidence in TESTING; an older green run does not verify newer changes.

## Findings closed by this audit

| Partial requirement found | Resulting implementation | Specific protection |
|---|---|---|
| Copilot could not summarize saved incidents | `IncidentBriefs`, assistant summary endpoint and selected-investigation UI | Actual medians/counts and trace/span citations only; no journal/postmortem/provider access or invented diagnosis |
| A global/family percentile could hide a slow payment operation | Fixed operation templates and bounded independent latency windows | 92 labels × 100 samples; IDs/queries never become labels; lifetime counters/windowed percentiles remain distinct |
| Rubric feedback was keyword coverage only | Topic-specific claim, failure case, concrete source/test and verification action; structured reference-matched observations | Evidence is self-reported and human correctness remains unverified; no automated competence score |
| ADR form fields were advertised as required but API accepted incomplete writes | API/form enforce six nonblank fields and strict calendar review date | Validation before SQL; legacy records stay readable, invalid edits leave existing state/history unchanged |

The prior navigation checkpoint additionally repaired inactive source-map buttons under drawing truncation, linked all fourteen learning areas to public implementations/walkthroughs, and verified modal input focus, Tab wrapping, Escape and focus return.

## All seventeen mandatory test categories

| Prompt category | Executable evidence |
|---|---|
| 1. Duplicate idempotency keys | PaymentIntegrationTest simultaneous authorization and immutable replay/conflict; `scripts/load.mjs` |
| 2. Concurrent updates | Concurrent capture/refund and optimistic engineering-document races |
| 3. Invalid transitions | PaymentRulesTest and PaymentIntegrationTest with unchanged durable effects |
| 4. Debit-credit balancing | Paired posting/reconciliation tests, including balanced but inconsistent amount/currency fixtures |
| 5. Partial/repeated refunds | PaymentIntegrationTest and RefundPropertiesTest |
| 6. Worker interruption/restart | DeliveryIntegrationTest both commit windows and `scripts/recovery-check.mjs` |
| 7. Duplicate event delivery | Inbox/receipt deduplication, duplicate worker delivery, three real signed HTTP receipts |
| 8. Retry exhaustion | RetryPolicyTest, durable dead-letter/redelivery cases and Systems browser flow |
| 9. Migrations | PostgreSQL/Flyway upgrade fixtures, preserved legacy evidence, restored application startup |
| 10. API validation | HTTP trust-boundary, body/JSON/diagram/document/import/ADR/UUID checks |
| 11. Authorization failures | Payment, workspace, repository, incident-summary and webhook-signature checks |
| 12. Repository path traversal | IntelligenceTest plus local/remote snapshot and GitHub archive validation |
| 13. Secret redaction | RedactionTest, long-token/line-citation regressions and bounded provider transport |
| 14. Provider outages | IntelligenceTest and real local stub HTTP provider failures/redirect/size limits |
| 15. Imported prompt injection | Instruction-like files excluded from retrieval; imported code remains unexecuted |
| 16. Frontend workflows | Chromium/Firefox tests against real Compose; intercepted transports are explicitly labelled |
| 17. Application restart recovery | New application contexts and actual API/JVM restart with durable effect, journal, saved spans and incident summary |

## Deliverables present

Source lives in `backend/src` and `frontend/src`; five SQL migrations in `backend/src/main/resources/db/migration`; deployment in `compose.yaml`, both Dockerfiles and nginx configuration; public configuration in `.env.example`; API contract in `docs/openapi.yaml`; automation in `.github/workflows/ci.yml` and `scripts/verify.ps1`. Architecture diagrams/decisions, security/recovery/dependency guidance, five demos with a presentation outline, scorecard, learning notes, sample/load/recovery/backup scripts and explicit limitations are in the repository. No empty module or placeholder endpoint is counted as a deliverable.

## Deliberate limits and unverified external assurance

- No live model/key is configured. Actual provider answer quality is unverified; the provider/bridge protocol, failure behavior, explicit matching-context consent and useful no-model behavior are tested. Incident summaries need no model. Source relationships remain conservative static evidence, not proof that a runtime branch ran.
- Java receives structured parsing; other indexed formats provide searchable source and conservative supported manifests. Unsupported/ambiguous relationships are omitted. GitHub import supports public default-branch roots at a pinned commit; private/enterprise imports and imported service execution are unsupported.
- One local engineer identity and loopback deployment are implemented. Shared OIDC/TLS, separate database roles, penetration testing, broad browser/manual assistive-technology review and production capacity/HA are separate operating work.
- Broker/saga/cache/breaker/SLO concepts use explicitly labelled deterministic models where real infrastructure is unnecessary. Local worker/database/webhook boundaries are real; no fleet or financial network claim follows.
- Backups automatically verified here contain newly generated isolated data. Existing-user-workspace restoration, encryption, off-machine retention and production recovery objectives require separate operator choices; this audit never restores over the user's data.
- JDBC, native SVG/HTML controls, disposable PostgreSQL, local OTel and lexical retrieval are documented alternatives to recommended JPA/React Flow/component/Testcontainers/collector/embedding stacks. Conditional Kafka/Redis/Kubernetes are not mandatory dependencies.

Read and personally change the named tests and failure cases before claiming understanding or authorship. The audit measures implementation evidence; it does not certify the user's learning or production readiness.
