# Recovery checks and logical restore drill

ATLAS has three distinct automated recovery checks. They establish different facts.

## Application restart

With Compose running and `ATLAS_API_TOKEN` set, run `node scripts/recovery-check.mjs`. The script pauses the local worker, creates a synthetic event, engineering note and measured investigation, commits the event's consumer effect without acknowledging delivery, and restarts this project's API container. It verifies one durable effect, inbox duplicate suppression, unchanged payment state, the engineering note, an empty in-memory repository index and identical archived investigation spans after restart. Those saved spans must be absent from the fresh JVM's live ring. It deletes no application records. This is an API/JVM restart, not a database crash or machine power-loss test.

## Isolated logical backup and restore

Run:

```powershell
node scripts/restore-drill.mjs
```

Requires Docker and the same PostgreSQL 17.11 image as Compose. The drill creates a uniquely named, labelled PostgreSQL container with no network, no published ports, and a tmpfs data directory. It never connects to `atlas`, `atlas_test`, either existing database container, or their volumes. It applies all five migrations directly to a new fixture database, then inserts only generated synthetic records, including archived incident evidence.

The fixture includes a captured payment and paired posting, an immutable response record, an outbox event still leased after its consumer effect, inbox/notification/webhook receipts, an engineering journal with two revisions and the saved worker clock. The script creates a custom-format `pg_dump`, restores it into another fresh database with `pg_restore --single-transaction --exit-on-error`, and compares the complete selected source/restored rows and ledger totals.

It additionally verifies that the restored unique capture constraint still rejects a duplicate, the identity sequence advances correctly, and deleting restored journal/investigation records cascades to their revisions/evidence. Before cleanup, the script checks both the exact container-name pattern and its per-run ownership labels. It removes only that newly created container. The dump is inside the disposable container and does not become a public artifact.

Executed local result on 19 October 2026: PASS for all checks, repeated after switching readiness to the final server's TCP listener instead of the entrypoint's temporary initialization socket. This drill is included in the Windows verification entry point and GitHub workflow. The [hosted verification job for the restore implementation](https://github.com/EklavyaGoyal17/ATLAS/actions/runs/39225309593/job/117970032149) also passed.

This verifies a logical schema/data round trip of generated fixtures. Migrations are tested by Flyway separately; the drill does not claim a restored Flyway history or validate actual application data. It does not measure production recovery-point/recovery-time objectives, physical crash recovery, encrypted backup storage, retention or off-machine disaster recovery.

## Application-created backup and restored API readback

With Docker running and the local API image built, run:

```powershell
node --test scripts/application-backup-support.test.mjs
node scripts/application-backup-drill.mjs
node scripts/application-backup-drill.mjs --failure-cleanup-check
```

This uses Node 24 and the existing `atlas-api:latest` / PostgreSQL 17.11 images. It creates a separate UUID-labelled internal Docker network and tmpfs database. It starts source and restored API processes sequentially, with scheduler/model transport disabled, no repository mounts and no published ports. Requests use bounded HTTP/1.0 over loopback inside the owned API container through literal Docker arguments; there is no shell command or existing service connection.

The real source API creates an authorized/captured/partially refunded payment, a committed-before-ack consumer effect, signed duplicate webhook receipt, journal revisions, diagram and saved investigation. Only this generated source API stops for a consistent backup. The custom dump includes Flyway history, and the SHA-256 manifest records byte length and image IDs. Output goes into a fresh `.runtime/application-backup-<UUID>` directory with exclusive file creation. Dumps are capped at 64 MiB; manifest/input reads and internal HTTP framing are bounded. Files remain ignored and contain generated data only. The checksum detects accidental change, not malicious replacement of both dump and manifest.

After restoring a fresh database, a new actual ATLAS process must validate Flyway and read back the same state. The drill checks all public-table row fingerprints and semantic sequence state, captured/refunded ledger totals, document revisions, diagram and identical archived evidence. It then exercises immutable idempotency replay, recovery without a second consumer effect, webhook deduplication, unique capture rejection, sequence advancement and revision/evidence cascades. Every destructive probe targets the disposable restored database.

The 23 October extension also creates a complete six-field ADR with two revision bodies and verifies identical revisions after restore. The saved incident summary must be recomputed identically by the restored API, with each cited child trace/span pair checked against retained evidence before backup. This exercises the derived assistant output as well as its durable inputs; it needs no model or repository access.

Ownership-checked cleanup removes only resources created by this run. The failure-cleanup command deliberately fails after its disposable database starts, then verifies removal. Failure output withholds payloads and assertion differences. The normal and injected-failure drills passed locally on 21 October 2026. The helper suite passed all 12 cases in Linux; Windows passed 11 and skipped its unavailable file-symlink capability. Two initial attempts failed port-binding validation; the final implementation uses no host ports. [ADR 015](DECISIONS/015-application-backup-readback.md) describes the trust boundary.

This proves restoration of **application-created generated workspaces**, including actual application startup. It does not restore the engineer's existing workspace, run a physical database crash, measure production RPO/RTO, encrypt backups or establish retention/off-machine recovery.

## Existing-workspace backup remains operator-controlled

To back up the running local Compose database without modifying it:

```powershell
$atlasBackupId = [guid]::NewGuid().ToString()
$atlasBackupFolder = Join-Path (Get-Location) ".runtime/operator-backup-$atlasBackupId"
New-Item -ItemType Directory -Path $atlasBackupFolder -ErrorAction Stop | Out-Null
$atlasContainerDump = "/tmp/atlas-backup-$atlasBackupId.dump"
docker compose exec -T db pg_dump -U atlas -Fc -f $atlasContainerDump atlas
if ($LASTEXITCODE -ne 0) { throw 'Backup creation failed' }
docker compose cp "db:$atlasContainerDump" (Join-Path $atlasBackupFolder 'atlas.dump')
if ($LASTEXITCODE -ne 0) { throw 'Backup copy failed' }
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $atlasBackupFolder 'atlas.dump')
```

The dump contains local notes and records; keep it private and outside Git. The unique output directory avoids reusing an earlier backup filename; retain and clean both copies deliberately. Restoration into an existing application database is a separate operator-approved operation. Validate the destination, stop writers and preserve the existing database before any replacement. Neither automated drill performs that operation.
