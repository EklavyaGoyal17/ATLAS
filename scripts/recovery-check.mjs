// Reproducible local Compose restart proof. Restarts this project's API; deletes no records.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
const token = process.env.ATLAS_API_TOKEN;
if (!token)
  throw new Error(
    "Set ATLAS_API_TOKEN for the running local Compose application",
  );
const root = fileURLToPath(new URL("../", import.meta.url));
const base = "http://127.0.0.1:8080";
async function api(path, method = "GET", body, key) {
  const response = await fetch(`${base}/api${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  const data = await response.json();
  assert.ok(response.ok, `${response.status}: ${JSON.stringify(data)}`);
  return data;
}
await api("/worker", "PUT", { running: false });
const recordId = randomUUID();
await api(`/workspace/documents/${recordId}`, "PUT", {
  kind: "JOURNAL",
  title: "Synthetic restart verification",
  expectedVersion: 0,
  body: {
    purpose: "Verify durable engineering records",
    evidence: "Generated local test data",
  },
});
const scenario = await api(
  "/experiments",
  "POST",
  { scenario: "CRASH_AFTER_COMMIT_ONCE" },
  randomUUID(),
);
const id = scenario.eventId;
const first = await api("/worker/step", "POST", {
  eventId: id,
  advanceTicks: 1,
});
assert.equal(first.outcome, "CRASH_AFTER_COMMIT");
const before = await api(`/events/${id}`);
assert.equal(before.event.effects, 1);
assert.equal(before.event.status, "PROCESSING");
const investigation = await api("/observability/incidents", "POST", {
  scenario: "SLOW_QUERY",
});
const evidencePath = `/observability/incidents/${investigation.id}/evidence`;
const evidenceBefore = await api(evidencePath);
assert.equal(evidenceBefore.available, true);
assert.ok(evidenceBefore.spans.some((span) => span.name === "db.query"));
console.log(
  `Committed one effect for ${id}; restarting only the ATLAS Compose API.`,
);
execFileSync(
  "docker",
  ["compose", "--env-file", ".env.example", "restart", "api"],
  { cwd: root, stdio: "inherit", timeout: 45000, windowsHide: true },
);
let ready = false;
for (let poll = 0; poll < 120; poll++) {
  try {
    const health = await fetch(`${base}/actuator/health`, {
      signal: AbortSignal.timeout(1000),
    });
    if (health.ok) {
      ready = true;
      break;
    }
  } catch {}
  await delay(250);
}
assert.ok(ready, "API did not become healthy after restart");
const records = await api("/workspace/documents?kind=JOURNAL");
assert.equal(
  records.find((record) => record.id === recordId)?.body.evidence,
  "Generated local test data",
);
assert.equal(
  (await api("/repository/configuration")).indexed,
  false,
  "Repository source must not automatically reappear after restart",
);
assert.equal(
  (await api("/observability/traces")).some((span) =>
    evidenceBefore.spans.some((saved) => saved.spanId === span.spanId),
  ),
  false,
  "Saved sample spans must not reappear in the new JVM's live ring",
);
assert.deepEqual(
  await api(evidencePath),
  evidenceBefore,
  "Saved investigation spans changed across restart",
);
const persisted = await api(`/events/${id}`);
assert.equal(persisted.event.effects, 1);
assert.equal(persisted.event.status, "PROCESSING");
const recovery = await api("/worker/step", "POST", {
  eventId: id,
  advanceTicks: 2,
});
assert.equal(recovery.outcome, "DELIVERED");
const after = await api(`/events/${id}`);
assert.equal(after.event.effects, 1);
assert.equal(after.event.duplicatesSuppressed, 1);
const payment = await api(`/payments/${after.event.paymentId}`);
assert.equal(payment.payment.status, "AUTHORIZED");
assert.equal(payment.postings.length, 0);
console.log(
  JSON.stringify(
    {
      eventId: id,
      before: before.event.status,
      after: after.event.status,
      effects: after.event.effects,
      duplicatesSuppressed: after.event.duplicatesSuppressed,
      paymentState: payment.payment.status,
      engineeringRecordPersisted: true,
      repositorySnapshotCleared: true,
      incidentEvidencePersisted: true,
      note: "Actual API container/JVM restart; injected interruption at commit-before-ack, not an OS power-loss test.",
    },
    null,
    2,
  ),
);
