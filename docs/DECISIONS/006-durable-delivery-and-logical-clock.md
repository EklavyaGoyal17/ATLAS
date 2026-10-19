# ADR 006: Durable outbox/inbox with a logical delivery clock

Context: payment state can commit while a downstream notification is delayed or delivered repeatedly. The lab needs inspectable failure boundaries and repeatable tests without a broker cluster.

Decision: write an immutable, versioned event envelope in the payment transaction. A separate scheduler claims one eligible outbox row per poll with `FOR UPDATE SKIP LOCKED`, a two-tick lease and a new fencing token. Three database transactions separate claim, consumer effect, and acknowledgement. The consumer inbox and local synthetic notification archive commit together; the event ID uniquely identifies the business effect.

The persisted clock advances on a manual step or an enabled scheduler poll. It does not advance while paused or while the application is stopped. Attempts are limited to three per delivery cycle, with retry delays of one then two ticks. A delivered/dead event can be explicitly redelivered; the envelope and inbox are retained. Faults are limited to predefined synthetic scenarios.

Alternatives: publishing outside the payment transaction loses events on a crash; marking delivery before consumer commit can lose effects; committing effects without an inbox permits duplicates. A real broker or external webhook adds deployment, network and SSRF concerns without improving the first correctness experiment. Wall-clock retries require waiting and make boundary tests harder to reproduce.

Tradeoffs: the global clock/control row serializes brief claims; it is an educational coordination boundary, not a high-throughput queue design. Multiple app instances advance the shared clock on each poll, so ticks are not seconds. Leases can expire while another worker is active; row locks, lease tokens, and the inbox protect against stale effects/acknowledgements. This is at-least-once processing with an idempotent consumer effect, not a general exactly-once delivery guarantee.

Consequences: delivery can be out of order, so the consumer archives immutable events rather than overwriting payment state. A delayed notification does not change ledger balances. Old V1 audit rows receive no fabricated event snapshots during upgrade. A simulated crash omits a commit/ack boundary; it does not kill the JVM. A separate restart script proves persistence across a real API process restart. External webhooks, multi-consumer fan-out, wall-clock production scheduling, storage retention and capacity limits remain future work.

Review: before real webhook delivery, multiple consumer types, or any capacity claim.
