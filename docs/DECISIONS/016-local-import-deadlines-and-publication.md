# ADR 016: Bound local import waiting and publish one consistent snapshot

Status: accepted, 21 October 2026.

## Context

File/count limits cannot bound filesystem latency or parser and database waits. Local indexing previously held a monitor while scanning and auditing, so an unusually slow import could also delay reads, deletion and publication by another import. Browser cancellation alone cannot prevent work on the server from publishing later.

## Decision

Keep the approved snapshot, local root and revision in one immutable atomic state. Local and GitHub imports construct drafts and record approval attempts outside publication locks. Only compare-and-set against the captured state can publish a replacement. Deletion immediately advances the revision and clears both snapshot and root, invalidating older drafts. Git inspection captures the same snapshot/root pair for both fixed commands.

Admit one local worker with no queue. The production ten-second processing budget includes draft construction and waiting for its approval audit. The request waits on the worker with the remaining budget and alone performs final publication after checking time and revision again. Timeout returns `503 LOCAL_IMPORT_TIMEOUT`; interruption returns `503 LOCAL_IMPORT_INTERRUPTED`; competing publication or deletion returns `409 IMPORT_SUPERSEDED`. A second local import while a worker remains active returns `429 LOCAL_IMPORT_BUSY`.

Cancellation interrupts the worker but does not release admission until that worker actually exits. This prevents repeated requests from accumulating workers when an operating-system, parser or JDBC operation ignores interruption. Reads and in-memory deletion need no worker monitor. HTTP 408 is reserved for an incomplete request message and can trigger automatic browser resending on reused connections. The completed import request instead reports its processing deadline through 503; see [RFC 9110 section 15.5.9](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.9) and [Chromium HTTP transaction handling](https://chromium.googlesource.com/chromium/src/+/HEAD/net/http/http_network_transaction.cc). The worker propagates the request correlation ID into its audit context and clears it on exit.

Retain the existing `REPOSITORY_APPROVED` and `REPOSITORY_GITHUB_APPROVED` event names for compatibility. They record approval attempts, not proof of successful publication. An audit may complete after timeout, deletion or replacement. Deletion's in-memory invalidation happens before its own audit, whose response can still wait on existing database timeouts.

## Alternatives

Increasing every browser timeout leaves late publication and monitor contention unresolved. Releasing admission when a future is cancelled permits abandoned operations to accumulate. Stopping Java threads forcibly risks corrupting shared state. Moving the entire import into a separately supervised process would provide stronger termination isolation but adds a process protocol and operational overhead beyond this local milestone.

## Consequences

Slow local work cannot publish from an abandoned worker or hold a publication monitor needed by readers. A hung worker can keep subsequent local imports busy until it returns; operators can restart the local API when necessary. JVM pauses and operating-system scheduling prevent a hard real-time response guarantee. This change does not add a CPU deadline to remote archive AST analysis, cancel an operation merely because its HTTP client disconnected, or promise forceful termination of blocking work.
