# Recovery checks and logical restore drill

ATLAS now has two distinct automated recovery checks. They establish different facts.

## Application restart

With Compose running and `ATLAS_API_TOKEN` set, run `node scripts/recovery-check.mjs`. The script pauses the local worker, creates a synthetic event and engineering note, commits the event's consumer effect without acknowledging delivery, and restarts this project's API container. It verifies one durable effect, inbox duplicate suppression, unchanged payment state, a persisted engineering note and an empty in-memory repository index after restart. It deletes no application records. This is an API/JVM restart, not a database crash or machine power-loss test.

## Isolated logical backup and restore

Run:

```powershell
node scripts/restore-drill.mjs
```

Requires Docker and the same PostgreSQL 17.11 image as Compose. The drill creates a uniquely named, labelled PostgreSQL container with no network, no published ports, and a tmpfs data directory. It never connects to `atlas`, `atlas_test`, either existing database container, or their volumes. It applies the repository's four migrations directly to a new fixture database, then inserts only generated synthetic records.

The fixture includes a captured payment and paired posting, an immutable response record, an outbox event still leased after its consumer effect, inbox/notification/webhook receipts, an engineering journal with two revisions and the saved worker clock. The script creates a custom-format `pg_dump`, restores it into another fresh database with `pg_restore --single-transaction --exit-on-error`, and compares the complete selected source/restored rows and ledger totals.

It additionally verifies that the restored unique capture constraint still rejects a duplicate, the identity sequence advances correctly, and deleting a restored journal record cascades to its revisions. Before cleanup, the script checks both the exact container-name pattern and its per-run ownership labels. It removes only that newly created container. The dump is inside the disposable container and does not become a public artifact.

Executed local result on 19 October 2026: PASS for all checks. This drill is included in the Windows verification entry point and GitHub workflow. The hosted outcome must be observed separately.

This verifies a logical schema/data round trip of generated fixtures. Migrations are tested by Flyway separately; the drill does not claim a restored Flyway history or validate actual application data. It does not measure production recovery-point/recovery-time objectives, physical crash recovery, encrypted backup storage, retention or off-machine disaster recovery.

## Actual application backup remains operator-controlled

To back up the running local Compose database without modifying it:

```powershell
docker compose exec db pg_dump -U atlas -Fc -f /tmp/atlas-backup.dump atlas
docker compose cp db:/tmp/atlas-backup.dump ./atlas-backup.dump
```

The dump contains local notes and records; keep it private and outside Git. Restoration into an actual application database is a separate operator-approved operation. Validate the destination, stop writers and preserve the existing database before any replacement. The automated drill does not perform that operation.
