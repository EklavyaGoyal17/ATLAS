# Engineering mentor notes

These notes describe what the implementation demonstrates. They do not claim the user authored or has learned this code.

## Demonstrated in source; tests establish the evidence

**Idempotency:** a unique key alone does not guarantee a stable response. `PaymentService.idempotent` locks the key, checks the request fingerprint, performs the action, and stores its response in one transaction. A separate reservation transaction would introduce a recovery problem. Failure mode: response serialization or SQL failure must roll back everything. Review question: why must a replay return the original version after capture? Real backend relevance: retrying a timed-out operation safely.

**Concurrency:** capture and refund decisions need to read the state under a database row lock. Java synchronization would protect only one process. Tradeoff: locking is easier to inspect but can create contention. Review question: why does a second capture with a different key fail, while the original key replays? Exercise: replace a row lock with optimistic version checks and prove equivalent behavior under contention.

**Accounting:** balanced does not imply correct. A missing capture posting can leave debit=credit=0 while payment state says captured. Reconciliation compares workflow totals to ledger totals per payment and currency. Review question: what extra invariant catches a posting in the wrong currency? Exercise: inject a mismatch in the disposable test database and write a repair proposal, not an automatic repair.

**Money:** the API accepts integer minor units, not decimal money strings or binary floating-point values. Bounds prevent arithmetic overflow within one payment; aggregate PostgreSQL numeric sums can still exceed a Java long in enormous datasets. Review question: how would zero/three-decimal currencies change the contract?

**Trust boundaries:** validation belongs on input; constraints defend durable state; output encoding belongs at rendering. A bearer-only API avoids browser-managed ambient authentication, permitting deliberate CSRF disablement. Review question: what changes if cookies are introduced? Exercise: implement token rotation without logging secrets.

## Explained but not implemented

External brokers, real distributed saga services, multi-region failover, production SLOs and live model answer quality remain outside the verified local implementation. Saga/breaker/cache/bulkhead concepts now have labeled deterministic models; they must not be presented as deployed distributed services.

## Durable delivery demonstrated in code and tests

**The dual-write problem:** `OutboxPublisher` requires the payment transaction and persists an immutable envelope before commit. Publishing after commit would create an event-loss window; publishing before commit to another system could expose an uncommitted payment. The outbox-insert rejection test proves all payment effects roll back together. Review question: why is an outbox preferable to retrying an external HTTP call inside the payment transaction?

**Effect commit is not acknowledgement:** `DeliveryService` deliberately separates claim, consumer commit and acknowledgement. A post-commit interruption leaves one notification but an unacknowledged lease. Recovery attempts delivery again; `consumer_inbox` prevents a second effect. Pre-commit interruption rolls both receipt and notification back. Review question: which fact proves whether the effect happened when the HTTP response is missing?

**Fencing and recovery:** every lease claim has a new token. Stale workers cannot apply, acknowledge or reschedule against a newer owner. A global logical clock makes leases/backoff repeatable, at the cost of serialized claims and artificial time. Review question: what changes when lease expiry uses wall-clock time across hosts?

**Retry is a policy, not a guarantee of success:** attempts are bounded, exponential backoff schedules later work, and exhausted deliveries require inspection/redelivery. The original envelope and receipt remain intact. Review question: why must manual redelivery retain the old inbox receipt?

**Dependency hygiene:** the scanner found vulnerable managed versions despite using a maintained framework. Coordinated patch overrides and a runtime package update were followed by regression tests and another scan. Review question: why does a passing npm audit say nothing about the Java image's packages?

Exercises: implement a second independent consumer with its own receipt key; add clock abstraction for a wall-clock adapter; measure contention on the global clock; design external webhook destination approval/signing without implementing unrestricted URL delivery. Read ADR 006 before claiming exactly-once delivery.

## Independent exercises

1. Read the migration and trace authorization from HTTP through commit. Draw the response-loss failure window.
2. Modify the decline threshold and update boundary tests.
3. Add authorization expiry with a clock abstraction and race it against capture.
4. Explain why per-currency amounts cannot be added into a single dashboard volume.
5. Run a duplicate burst, inspect audit rows, and write a short incident-style observation using only synthetic data.

Before claiming understanding, explain rollback, lock ordering, response replay, partial refunds, snapshot reads, and why this monolith is a deliberate choice.

## Investigation, safe comprehension and engineering records

**Measured observability:** Telemetry uses the real OTel SDK, JDBC timings and W3C context. Baseline/affected samples support a hypothesis; an injected delay only proves the controlled cause. Tradeoff: bounded local memory is easy to inspect but expires on restart. Review question: what does p95 mean with only a small retained sample? Exercise: collect a representative workload and define an SLO before claiming one.

**Safe source analysis:** RepositoryIndex never runs repository code. JavaParser establishes syntax and limited potential-call links. Ambiguous receivers remain unknown. RepositoryMetadata disables XML entities; manifest declarations are not resolved dependencies. Review question: when can a method-name/arity match still be misleading? Exercise: add a shadowed-field/overload fixture and verify no invented edge.

**Grounded explanations:** AssistantService ranks safe redacted excerpts and requires exact content-hashed IDs before optional transmission. Provider outage and invalid citations fall back to evidence. Citation identity does not establish semantic correctness. Review question: why are prompt instructions alone insufficient against malicious repository text? Exercise: add an adversarial fixture, inspect what is excluded, and independently verify an explanation against tests.

**Versioned decisions:** Documents uses database compare-and-set versions, a bounded revision history and metadata-only audit. The diagram editor's topology findings state assumptions instead of capacity claims. Journal progress is self-reported; rubric keyword coverage does not establish understanding. Review question: when should a stale edit be merged rather than retried unchanged? Exercise: open two editor sessions, reproduce 409, and propose a merge UX.

These are demonstrated implementation concepts, not claims that the user authored or mastered them. Read ADRs 007–009 and the generated tests, then personally modify and explain one failure boundary before presenting this project.
