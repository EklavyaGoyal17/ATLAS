# ADR 015: Verify generated application backups through a restored API

Status: accepted, 21 October 2026.

## Context

The original logical restore drill tests schema/data fixtures directly. It does not restore Flyway history or prove that an ATLAS process can open and use the restored database. Export and backup need stronger evidence without selecting, replacing or exposing an existing engineer's workspace.

## Decision

Create a new labelled internal Docker network and tmpfs PostgreSQL instance for each application backup drill. Start the locally built API image by its inspected image ID, with no repository mounts, no published ports, a disabled scheduler and an empty model endpoint. Send bounded HTTP/1.0 requests through literal `docker exec … nc` arguments to that container's own loopback interface. HTTP authorization headers and JSON request bodies travel on stdin rather than shell commands or command arguments. Fresh synthetic credentials are provisioned separately through the generated containers' environment arguments.

Generate payments, delivery state, signed receipt deduplication, journal revisions, a diagram and an archived investigation through the real API. Stop only this generated source API before capturing row/semantic-sequence fingerprints and a custom-format logical dump. Include Flyway history. Write the dump and SHA-256 manifest exclusively into a new ignored per-run directory, with a 64 MiB dump cap and bounded manifest reads. Refuse overwrite, changed lengths/checksums, symlink inputs and unexpected names. A checksum detects accidental corruption; it is not a signature against an attacker replacing both files.

Restore into an allowlisted fresh database in the same disposable PostgreSQL instance, with single-transaction/error-on-failure restore options. Start a second actual API and verify unchanged durable readback, immutable idempotent replies, worker recovery, receipt deduplication, constraints, identity advancement and deletion cascades. Sequence checks compare `last_value` and `is_called`, not physical WAL bookkeeping such as `log_cnt`.

Cleanup validates resource kind, exact UUID-derived name, purpose and run labels, plus internal network ownership, before removing only that run's resources. An injected-failure command exercises real cleanup. Failure summaries report a whitelisted phase/category and numeric script callsite; assertion diffs, SQL/API rows, dump bytes and credentials are withheld.

## Alternatives

Restoring the active workspace in place would require a separate destructive operation and could overwrite user records. Copying an existing workspace for acceptance would access notes that are unnecessary to test this boundary. A schema-only drill cannot establish application startup or durable API semantics. Publishing host API ports was unnecessary; internal loopback transport keeps the drill isolated.

## Consequences

The project gains a repeatable, application-level restore acceptance check and portable checksum/ownership helpers without new packages or database migrations. Retained artifacts contain generated data only and stay under ignored `.runtime`. Actual existing-workspace backups and destination selection remain operator-controlled. Encryption, off-machine retention, physical power loss, production recovery objectives and hostile concurrent filesystem replacement remain outside this claim.
