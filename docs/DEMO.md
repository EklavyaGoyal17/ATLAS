# Showcase and review walkthrough

Only implemented demonstrations are marked runnable. All data and observations are synthetic. Start the API/console as in README and set `ATLAS_API_TOKEN` in your terminal.

## Runnable: payment lifecycle and idempotency under pressure

1. `node scripts/seed.mjs` creates five synthetic scenarios (repeatable with stable keys): captured USD, partially refunded EUR, reversed INR, declined USD, and authorized USD. No automatic seeding or hidden metrics.
2. In the console, create an authorization for 12500 USD minor units. Inspect one AUTHORIZED event and no ledger posting. Close the detail, select **Replay last authorization**, verify the same payment ID.
3. Capture, verify one paired posting. Refund 2500, verify a second paired posting and a remaining refundable amount of 10000. Close and open Ledger: USD, EUR and INR remain separate, with zero mismatched payments.
4. `node scripts/load.mjs` submits 25 simultaneous identical requests with one key. Expected output: one 201, twenty-four 200s, one distinct payment ID. Repeat runs use new keys.
5. Open the reported payment in the console; its audit timeline must contain just one authorization event.

Automated proof: `simultaneousDuplicateAuthorizationsHaveOneEffect`, `differentKeysCannotCaptureTwice`, `concurrentRefundsNeverExceedCapture`, and both Playwright flows. Reconciliation and history are derived from stored rows, never illustrative dashboard numbers.

## Runnable: atomic rollback and state recovery

This portion proves payment rollback and persisted responses. The worker interruption/restart demonstration is implemented separately below.

```powershell
mvn -B -f backend/pom.xml test '-Dtest=PaymentIntegrationTest#postingFailureRollsBackStateAuditAndIdempotency'
mvn -B -f backend/pom.xml test '-Dtest=PaymentIntegrationTest#secondApplicationContextRecoversCommittedResponseAndHistory'
```

The first deliberately inserts a conflicting posting **only in atlas_test**, forces capture to fail mid-transaction, then proves payment state/audit/response did not partially advance. The second starts a fresh app context and retrieves the same durable response with no shared process memory. Neither asserts a real OS crash happened.

For a manual restart: create a payment, copy its URL/ID, stop only your API process, rerun the API command, reconnect and open that URL. State and audit must survive. The database has to remain running; PostgreSQL crash recovery is not tested by restarting only the app.

## Runnable: balanced but inconsistent ledger investigation

```powershell
mvn -B -f backend/pom.xml test '-Dtest=PaymentIntegrationTest#reconciliationDetectsBalancedButInconsistentRecords'
```

This test changes a capture posting in atlas_test: debit and credit remain equal, but the business amount mismatches. It then changes currency and verifies reconciliation detects that, too. No automatic repair endpoint exists. This demonstrates an accounting investigation, not distributed tracing or a production incident.

## Runnable: worker interruption and durable recovery

Open **Systems lab**, prepare **Crash after commit**, and step once. Verify PROCESSING with one effect. Advance two ticks and step again: verify DELIVERED, one effect, one suppressed duplicate. Inspect the persisted timeline, original envelope and unchanged payment. Repeat with the pre-commit interruption to see zero effects until recovery. Retry exhaustion demonstrates bounded backoff, dead letters and manual redelivery.

For real process recovery, run `node scripts/recovery-check.mjs` with the Compose app running and `ATLAS_API_TOKEN` configured. It restarts only the API container between the effect commit and recovery, then asserts a single effect survives. [DELIVERY](DELIVERY.md) contains the exact walkthrough, three-transaction diagram and limitations. Crash injection does not itself kill the JVM; the script performs a separate actual restart. No power-loss or broker failover result is claimed.

## Demo 3 — Measured incident investigation

Open Observability. Run SLOW_QUERY: three baseline and three affected measurements appear. Choose an affected sample and inspect its PostgreSQL db.query child span, duration, status and log correlation ID. Compare baseline spans. Record a hypothesis, validation plan and corrective action, then save a postmortem and open it in Engineering growth. Repeat with LOCK_CONTENTION and DOWNSTREAM_TIMEOUT to distinguish a real database wait from a controlled local dependency deadline. Five additional recipes expose retry, queue, cache, connection-budget and timeout-policy effects. Labels identify simulated dependencies; no production incident claim is made.

Verification: every incident recipe runs in EngineeringIntegrationTest; browser investigations save postmortems and reopen/filter/export durable spans after reload. Select a Saved investigation, open one baseline or affected sample, and compare its independent trace. Switch between Show live spans and Show saved evidence: the source label distinguishes them. The API restart check compares archived spans across a fresh JVM; the new live ring must not contain those old spans. Historical investigations without saved spans explicitly report that their expired evidence cannot be reconstructed. Exports include the snapshot and its retention/truncation status.

Run CONNECTION_EXHAUSTION to saturate a separate real one-slot PostgreSQL pool. Each affected sample reports POOL_TIMEOUT_RECOVERED: inspect the failed db.pool.acquire span (250 ms timeout) and successful db.pool.recovered span after releasing the connection. The application pool remains available; the probe pool closes after the investigation. This verifies a controlled local pool boundary, without claiming application-wide exhaustion.

## Demo 4 — Understand the ATLAS codebase

Open Code intelligence, approve relative path `.` and import the read-only checkout. Search `authorize`. Open the endpoint symbol in PaymentController, then follow the source-supported potential call to PaymentService.authorize and its Transactional boundary. Inspect PaymentStore declarations and the type graph, then cross-check actual HTTP/JDBC trace evidence. Review manifest dependencies and maintainability findings. The published checkout now has real commits; select a file to inspect its bounded history and any tracked working-tree diff. Try `../outside` and verify rejection. No source builds or scripts execute.

Verification: parser, path/approval, link, bounds, secret, prompt-injection and XML entity tests; repository browser workflow. Supported call links are hypotheses from syntax and arity, not dynamic call-graph guarantees.

## Demo 5 — Grounded technical review

In AI engineer, ask “Where is payment authorization transactional?” and select Find local evidence. Inspect cited endpoint/transaction declarations, supported potential calls and conditional investigation steps. Ask “Generate a test plan for concurrent refunds” to see proposed race, rollback and lost-response checks; they are suggestions, not completed tests. Preview provider context: no transmission occurs. With no configured provider the action remains disabled. A configured bridge requires matching question/snapshot/context-bound IDs and explicit transmission approval. Edit the question or reimport: previous answers/previews disappear, and late responses cannot restore them. Outage and invalid citations return local evidence. No live model response is presented as verified in this delivery.

Verification: consent, redaction, instruction isolation, changed context, provider outage and invalid-citation tests; real local stub transport; browser source citations. See AI_AND_IMPORT for optional API/Ollama setup. Technical review mode in Engineering growth also saves answers against an explicit evidence rubric; its term coverage is not a correctness grade.

## Concise engineering presentation outline

1. Problem: learn correctness through inspectable, reproducible backend workflows.
2. Architecture: one Spring app, one PostgreSQL database, a same-origin React console.
3. Hard problem: HTTP retries and concurrency; show key locking, row locking and atomic response persistence.
4. Accounting: paired postings prevent one-sided effects; reconciliation catches balanced business inconsistencies.
5. Decisions: monolith, explicit JDBC, bounded integer money, local bearer auth; defend alternatives using ADRs.
6. Evidence: actual test counts and local duplicate burst from TESTING. Explain the limits of a single-machine run.
7. Reliability: demonstrate outbox/inbox, post-commit interruption, stale-lease fencing, retry exhaustion and actual API restart recovery.
8. Investigation and growth: show measured incident traces, approved source navigation, consent-bound citations, editable diagrams and a saved engineering decision. State the synthetic/local boundaries and remaining hosted/model verification.

The code was generated with assistant help. Read, test, and personally modify the important paths before presenting them as work you can defend.
