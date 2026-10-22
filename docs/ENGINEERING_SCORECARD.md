# Engineering scorecard

Conservative self-assessment of the delivered local platform, on a 0–10 scale. These are judgments, not certifications or performance measurements. REQUIREMENTS maps the original request to concrete behavior and limitations.

| Area | Rating | Evidence | Remaining operating/deployment gap |
|---|---:|---|---|
| Correctness | 8 | Atomic idempotent payments, row/key locks, paired ledger, reconciliation, generated refund sequences, document compare-and-set | One fictional merchant, full capture only; no expiry/settlement/FX |
| Reliability | 7 | Durable outbox/inbox, lease fencing, bounded retries, signed duplicate HTTP receipt, fresh-JVM recovery, SQL-fixture restore and application-created backup with restored API readback | One database, no HA/broker failover/OS power-loss or existing-user-workspace restore; encryption/retention remain operator work |
| Security | 7 | Stateless local auth, rate/body budgets, safe import/redaction, XML entity rejection, content-bound model approval, no model execution tools, runtime scans | Local single identity, heuristic masking, no TLS/pen-test/shared roles; stable checkout required |
| Tests | 8 | Full host/Java 21 Linux suites, Chromium and Firefox real-Compose workflows, concurrency/migrations/restart, pool timeout/recovery, stale-response consent checks | WebKit and manual assistive-technology review absent; limited adversarial corpus and workload breadth |
| Architecture | 8 | Six backend packages, explicit transaction boundaries, typed provider, seventeen ADRs, source-evidenced cycle/coupling and impact review, bounded public GitHub adapter, central API contract | Static graph covers a conservative Java subset; hand-maintained DTO contract and single database boundary |
| Observability | 7 | OTel HTTP/JDBC/W3C evidence, measured global/operation distributions, cited deterministic incident summaries, queue state, bounded durable sample spans, incident comparisons and postmortems | General live spans expire; saved snapshots are bounded and synthetic dependency recipes are not fleet monitoring |
| Performance | 5 | Bounded imports/pages/queues/requests, database deadlines, repeatable duplicate/read load scripts | Small warm local runs; no capacity or production SLO claim |
| Documentation | 8 | Setup/fallback, architecture, decisions, security/privacy, five runnable demos, original requirement mapping and measured results | Independent new-developer onboarding and restore review remain |
| User experience | 7 | Responsive dark console, tested dialog focus/Tab wrapping/return and keyboard palette, source-map navigation under truncation, public learning links, actual data, saved notes/versions and exports | No manual assistive-technology review or broad browser matrix |
| Maintainability | 7 | Immutable records, bounded features, modular components, tested failure boundaries and pinned dependencies | Further package decomposition, API type generation and operational retention need ongoing work |
| Usefulness | 8 | Connected payment, incident, source, assistant, design and growth workflows; hosted CI and isolated restore verified | Live model quality and shared deployment assurance require external setup |

TESTING contains executed results. A configured workflow is not a hosted CI run; a validated citation ID is not proof of an explanation; a logical simulation is not a deployed distributed service; heuristic rubric coverage is not a competence score. The user should read, run and personally modify important paths before presenting them as work they understand.
