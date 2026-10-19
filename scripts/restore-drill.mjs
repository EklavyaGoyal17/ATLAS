// Restore only generated fixtures in a new, labelled, disposable PostgreSQL container.
// Never connects to the application database, test database, or their Docker volumes.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const runId = randomUUID();
const name = `atlas-restore-drill-${runId}`;
const source = "atlas_restore_fixture";
const restored = "atlas_restore_copy";
const image = "postgres:17.11-alpine";
let created = false;
function docker(args, input) {
  return execFileSync("docker", args, {
    cwd: root,
    input,
    encoding: "utf8",
    timeout: 90000,
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  }).trim();
}
function sql(database, text) {
  assert.ok(
    [source, restored].includes(database),
    "Fixture database allowlist",
  );
  return docker(
    [
      "exec",
      "-i",
      name,
      "psql",
      "-X",
      "-q",
      "-A",
      "-t",
      "-U",
      "atlas",
      "-d",
      database,
      "-v",
      "ON_ERROR_STOP=1",
    ],
    text,
  );
}
const payment = randomUUID(),
  event = randomUUID(),
  posting = randomUUID();
const document = randomUUID(),
  correlation = randomUUID(),
  lease = randomUUID();
const envelope = JSON.stringify({
  schemaVersion: 1,
  paymentId: payment,
  eventType: "CAPTURED",
  status: "CAPTURED",
  currency: "USD",
  amountMinor: 12500,
  capturedMinor: 12500,
  refundedMinor: 0,
  paymentVersion: 1,
  correlationId: correlation,
});
const documentBody = JSON.stringify({
  purpose: "Synthetic restore drill",
  evidence: "A durable journal revision",
});
const snapshot = `SELECT json_build_object(
 'payment',(SELECT row_to_json(p) FROM payment p WHERE id='${payment}'),
 'posting',(SELECT row_to_json(p) FROM ledger_posting p WHERE id='${posting}'),
 'event',(SELECT row_to_json(e) FROM outbox_event e WHERE event_id='${event}'),
 'idempotency',(SELECT row_to_json(i) FROM idempotency_record i WHERE request_key='restore-drill-intent'),
 'inbox',(SELECT row_to_json(i) FROM consumer_inbox i WHERE event_id='${event}'),
 'notification',(SELECT row_to_json(n) FROM notification n WHERE event_id='${event}'),
 'webhook',(SELECT row_to_json(w) FROM webhook_receipt w WHERE event_id='${event}'),
 'document',(SELECT row_to_json(d) FROM workspace_document d WHERE id='${document}'),
 'revisions',(SELECT json_agg(r ORDER BY version) FROM document_revision r WHERE document_id='${document}'),
 'worker',(SELECT row_to_json(w) FROM worker_control w WHERE id=1),
 'debits',(SELECT sum(debit_minor) FROM ledger_entry WHERE currency='USD'),
 'credits',(SELECT sum(credit_minor) FROM ledger_entry WHERE currency='USD'),
 'auditSequence',(SELECT max(sequence) FROM audit_event))::text;`;
try {
  docker([
    "run",
    "--detach",
    "--rm",
    "--name",
    name,
    "--label",
    "dev.atlas.purpose=restore-drill",
    "--label",
    `dev.atlas.run=${runId}`,
    "--network",
    "none",
    "--tmpfs",
    "/var/lib/postgresql/data",
    "-e",
    `POSTGRES_DB=${source}`,
    "-e",
    "POSTGRES_USER=atlas",
    "-e",
    "POSTGRES_PASSWORD=atlas-synthetic-restore-only",
    image,
  ]);
  created = true;
  let ready = false;
  for (let attempt = 0; attempt < 80; attempt++) {
    // The entrypoint's temporary init server accepts Unix sockets before bootstrap finishes.
    // TCP readiness waits for the final server, preventing restore work racing initdb shutdown.
    try {
      docker([
        "exec",
        name,
        "pg_isready",
        "-h",
        "127.0.0.1",
        "-U",
        "atlas",
        "-d",
        source,
      ]);
      ready = true;
      break;
    } catch {
      await delay(250);
    }
  }
  assert.ok(ready, "Isolated PostgreSQL readiness deadline");
  const migrationFolder = new URL(
    "../backend/src/main/resources/db/migration/",
    import.meta.url,
  );
  const migrations = (await readdir(migrationFolder))
    .filter((file) => /^V\d+__.*\.sql$/.test(file))
    .sort(
      (a, b) => Number(a.match(/^V(\d+)/)[1]) - Number(b.match(/^V(\d+)/)[1]),
    );
  assert.ok(migrations.length > 0, "No schema migrations found");
  for (const file of migrations)
    sql(source, await readFile(new URL(file, migrationFolder), "utf8"));
  sql(
    source,
    `BEGIN;
    INSERT INTO payment(id,merchant_id,amount_minor,currency,status,captured_minor,version) VALUES('${payment}','merchant_demo',12500,'USD','CAPTURED',12500,1);
    INSERT INTO ledger_posting(id,payment_id,kind,debit_account,credit_account,amount_minor,currency) VALUES('${posting}','${payment}','CAPTURE','acquirer_receivable','merchant_payable',12500,'USD');
    INSERT INTO audit_event(id,payment_id,type,detail,correlation_id) VALUES('${event}','${payment}','CAPTURED','Synthetic backup fixture','${correlation}');
    INSERT INTO idempotency_record(merchant_id,request_key,fingerprint,response_json) VALUES('merchant_demo','restore-drill-intent',repeat('a',64),'${envelope}');
    INSERT INTO outbox_event(event_id,payment_id,event_type,payload_json,status,attempts,lease_token,lease_until_tick) VALUES('${event}','${payment}','CAPTURED','${envelope}','PROCESSING',1,'${lease}',9);
    INSERT INTO consumer_inbox(event_id,first_attempt,processed_tick) VALUES('${event}',1,7);
    INSERT INTO notification(event_id,payment_id,payload_json) VALUES('${event}','${payment}','${envelope}');
    INSERT INTO webhook_receipt(event_id,payload_sha256) VALUES('${event}',repeat('b',64));
    INSERT INTO workspace_document(id,kind,title,version,body_json) VALUES('${document}','JOURNAL','Synthetic restore record',2,'${documentBody}');
    INSERT INTO document_revision(document_id,version,title,body_json) VALUES('${document}',1,'Synthetic first revision','{}'),('${document}',2,'Synthetic restore record','${documentBody}');
    UPDATE worker_control SET current_tick=7,running=false WHERE id=1;
    COMMIT;`,
  );
  const before = JSON.parse(sql(source, snapshot));
  docker([
    "exec",
    name,
    "pg_dump",
    "-U",
    "atlas",
    "-d",
    source,
    "--format=custom",
    "--file=/tmp/atlas-fixture.dump",
  ]);
  docker(["exec", name, "createdb", "-U", "atlas", restored]);
  docker([
    "exec",
    name,
    "pg_restore",
    "-U",
    "atlas",
    "--dbname",
    restored,
    "--single-transaction",
    "--exit-on-error",
    "/tmp/atlas-fixture.dump",
  ]);
  const after = JSON.parse(sql(restored, snapshot));
  assert.deepEqual(
    after,
    before,
    "Restored durable state differs from the source fixture",
  );
  assert.equal(after.debits, after.credits);
  assert.equal(after.event.status, "PROCESSING");
  assert.equal(after.inbox.event_id, event);
  assert.equal(after.document.version, 2);
  assert.equal(after.revisions.length, 2);
  let rejected = false;
  try {
    sql(
      restored,
      `INSERT INTO ledger_posting(id,payment_id,kind,debit_account,credit_account,amount_minor,currency) VALUES('${randomUUID()}','${payment}','CAPTURE','acquirer_receivable','merchant_payable',12500,'USD');`,
    );
  } catch (error) {
    rejected = /one_capture_per_payment/.test(error.stderr?.toString() ?? "");
  }
  assert.ok(
    rejected,
    "Restored unique capture constraint must reject a duplicate",
  );
  const nextSequence = Number(
    sql(
      restored,
      `INSERT INTO audit_event(id,payment_id,type,detail,correlation_id) VALUES('${randomUUID()}','${payment}','RESTORE_CHECK','Verify restored identity sequence','${correlation}') RETURNING sequence;`,
    ),
  );
  assert.ok(
    nextSequence > after.auditSequence,
    "Identity sequence was not restored",
  );
  assert.equal(
    sql(
      restored,
      `DELETE FROM workspace_document WHERE id='${document}'; SELECT count(*) FROM document_revision WHERE document_id='${document}';`,
    ),
    "0",
    "Restored revision deletion must cascade",
  );
  console.log(
    JSON.stringify(
      {
        result: "PASS",
        image,
        migrations: migrations.length,
        verified: [
          "payment and balanced ledger",
          "immutable replay record",
          "outbox lease and inbox effect",
          "webhook receipt",
          "journal and revisions",
          "worker clock",
          "unique constraints",
          "identity sequence",
          "cascade deletion",
        ],
        scope:
          "pg_dump/pg_restore of generated fixtures only; new network-isolated tmpfs container, no application/test databases or volumes accessed. No encryption, physical crash, production RPO/RTO or actual application-data backup claim.",
      },
      null,
      2,
    ),
  );
} finally {
  if (created) {
    assert.match(name, /^atlas-restore-drill-[a-f0-9-]{36}$/);
    const labels = JSON.parse(
      docker(["inspect", "--format", "{{json .Config.Labels}}", name]),
    );
    assert.equal(labels["dev.atlas.purpose"], "restore-drill");
    assert.equal(labels["dev.atlas.run"], runId);
    docker(["rm", "--force", name]);
  }
}
