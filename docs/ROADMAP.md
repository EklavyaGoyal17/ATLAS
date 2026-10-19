# Delivery scope and acceptance

All seven module areas have implementations in the local single-user platform. TESTING records executed checks separately from configured or unavailable external checks. No production-readiness claim is made.

| Phase | Delivered local behavior | Verification |
|---|---|---|
| 0 | Discovery, journeys, modular boundaries, security/test plan, nine ADRs | ARCHITECTURE and DECISIONS |
| 1 | React/Java/PostgreSQL/Compose slice, durable authorization history and idempotent responses | Actual containers, HTTP integration and browser workflows |
| 2 | Capture, partial/repeated refunds, reversal, paired ledger and reconciliation | Races, rollback, invalid transitions, generated refund sequences and controlled inconsistency tests |
| 3 | Outbox/inbox, scheduler/manual stepping, leases/fencing, retry/dead letters, signed loopback HTTP webhooks, twelve discrete-event models | Delivery/duplicate receipt tests; deterministic invariants over 100 seeds per model; real API restart script |
| 4 | OTel HTTP/JDBC spans, W3C propagation, measured latency/counters, actual queue metrics, eight controlled incidents including isolated real PostgreSQL pool exhaustion/recovery, saved postmortems | Trace/correlation checks, bounded exporter test, all incident recipes, pool cleanup and browser investigation |
| 5 | Approved read-only import, path/link/size controls, redaction, Java AST symbols, dependency map, supported potential calls, manifest inventory, Git history/diff | Security/parser/XXE tests and browser navigation; Linux symlink check is required |
| 6 | Cited local declarations/potential calls and suggested investigation/test/review steps, optional API/local bridge, question/snapshot/context-bound approval, no execution tools | Redaction, prompt injection, consent invalidation, citation bounds, stale browser responses and real stub transport tests; live model quality unverified |
| 7 | Editable diagrams/connections/responsibilities, six templates, versions/exports; journals/ADRs, fourteen learning areas, file-linked exercises and rubric reviews | Optimistic document races, revisions/deletion/bounds and actual browser save/export/reload flows |
| 8 | Local authentication/rate budget, bounded API/database resources, responsive keyboard controls, Chromium/Firefox browser matrix, vulnerability gates, small load scripts and five documented demos | Local evidence in TESTING; hosted CI and deployment-specific assurance remain separate |

## External acceptance and deliberate limits

- The repository is published at `EklavyaGoyal17/ATLAS`. Hosted verification and source/runtime-image vulnerability gates passed after fixing a Maven Central dependency-discovery rate limit. TESTING links the observed runs; the README badge shows the current branch result.
- No live model is installed and no API credentials were supplied. Synthetic local stubs verify the bridge contract and failure behavior. Provider answer quality and data handling require separate evaluation and explicit transmission approval.
- This installation has one loopback-bound engineer identity. A shared deployment requires scoped user identities, TLS, separated migration/runtime database roles, rotation and independent security review. Those protections are not claimed here.
- Connection exhaustion measures a real isolated one-slot Hikari/PostgreSQL pool; it preserves the application pool. Dependency delays and logical compensation/cache/breaker/SLO models remain synthetic. They do not prove broker failover, global quotas, multi-region consistency or production capacity.
- The signed HTTP receiver uses loopback only. External webhook destinations remain unsupported; destination approval/SSRF, independent signing keys, external retries and receiver idempotency need a separate adapter.
- Java analysis is conservative and syntax based. Other allowed formats are searchable; ambiguous types/overloads, dynamic dispatch and actual branch execution remain unknown. Masking cannot guarantee all secrets are found. Keep the approved checkout stable.
- Telemetry resets on JVM restart; investigations/records persist. An isolated logical restore of generated fixtures now verifies schema/data, constraints, identity sequences and revision cascades. Actual application-backup restoration, encrypted/off-machine retention, larger workloads, broad browser testing, penetration testing and manual assistive-technology review remain operating work.

No destructive reset or external deployment runs automatically. REQUIREMENTS maps the original request to source, demonstrations, tests and limitations.
