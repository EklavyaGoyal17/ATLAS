// Generated workspaces only: never reads the running application/test database or its volumes.
// All database writes, API calls, stops and cleanup target resources created by this invocation.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, realpath } from "node:fs/promises";
import { join, relative, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import {
  writeBackup,
  verifyBackup,
  assertOwned,
  cleanupOwned,
  MAX_DUMP_BYTES,
  PURPOSE,
  failureSummary,
  parseLocalHttp,
} from "./application-backup-support.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const args = process.argv.slice(2);
assert.ok(
  args.length === 0 ||
    (args.length === 1 && args[0] === "--failure-cleanup-check"),
  "Only --failure-cleanup-check is supported; existing databases are never accepted",
);
const failureCheck = args.length === 1;
const runId = randomUUID();
const prefix = `atlas-app-backup-${runId}`;
const network = { kind: "network", name: `${prefix}-net` };
const db = { kind: "container", name: `${prefix}-db` };
const source = { kind: "container", name: `${prefix}-source` };
const restored = { kind: "container", name: `${prefix}-restored` };
const resources = [];
const postgresImage = "postgres:17.11-alpine",
  apiImage = "atlas-api:latest";
const sourceDatabase = "atlas_backup_generated",
  restoredDatabase = "atlas_backup_restored";
const token = "atlas-isolated-backup-" + randomUUID();
const password = "atlas-isolated-db-" + randomUUID();
let outputDirectory;
let phase = "preflight";

function docker(arguments_, input, binary = false) {
  try {
    return execFileSync("docker", arguments_, {
      cwd: root,
      input,
      encoding: binary ? null : "utf8",
      timeout: 120000,
      maxBuffer: MAX_DUMP_BYTES,
      windowsHide: true,
      stdio: ["pipe", "pipe", "pipe"],
    });
  } catch (error) {
    // Do not expose command arguments, transient credentials, SQL rows or API bodies.
    const failure = new Error(
      `Docker ${arguments_[0]} operation failed (status ${error.status ?? "timeout"})`,
    );
    failure.missingResource =
      /No such (?:object|container|network)|network [^\r\n]+ not found/i.test(
        error.stderr?.toString() ?? "",
      );
    throw failure;
  }
}

function inspect(resource) {
  try {
    return JSON.parse(docker([resource.kind, "inspect", resource.name]))[0];
  } catch (error) {
    if (error.missingResource) return null;
    throw error;
  }
}

function owned(resource) {
  const state = inspect(resource);
  assert.ok(state, "Disposable resource must exist");
  assertOwned(resource, state, runId);
  return state;
}

function labels() {
  return [
    "--label",
    `dev.atlas.purpose=${PURPOSE}`,
    "--label",
    `dev.atlas.run=${runId}`,
  ];
}

function remove(resource) {
  owned(resource);
  docker(
    resource.kind === "network"
      ? ["network", "rm", resource.name]
      : ["container", "rm", "--force", resource.name],
  );
}

function sql(database, statement) {
  owned(db);
  assert.ok(
    [sourceDatabase, restoredDatabase].includes(database),
    "Generated database allowlist",
  );
  return docker(
    [
      "exec",
      "-i",
      db.name,
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
    statement,
  ).trim();
}

async function waitFor(check, description, attempts = 90) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      if (await check()) return;
    } catch {
      /* Bounded startup polling only; no workflow retry. */
    }
    await delay(500);
  }
  throw new Error(`${description} readiness deadline exceeded`);
}

async function startApi(resource, database, imageId) {
  owned(db);
  owned(network);
  assert.ok(
    [sourceDatabase, restoredDatabase].includes(database),
    "Generated database allowlist",
  );
  resources.push(resource);
  docker([
    "run",
    "--detach",
    "--pull=never",
    "--name",
    resource.name,
    ...labels(),
    "--network",
    network.name,
    "--memory",
    "768m",
    "--cpus",
    "1",
    "--pids-limit",
    "256",
    "-e",
    `ATLAS_API_TOKEN=${token}`,
    "-e",
    `ATLAS_DB_URL=jdbc:postgresql://${db.name}:5432/${database}`,
    "-e",
    `ATLAS_DB_PASSWORD=${password}`,
    "-e",
    "ATLAS_DB_USER=atlas",
    "-e",
    "ATLAS_BIND_ADDRESS=0.0.0.0",
    "-e",
    "ATLAS_WORKER_SCHEDULER_ENABLED=false",
    "-e",
    "ATLAS_MODEL_ENDPOINT=",
    "-e",
    "ATLAS_MODEL_KEY=",
    "-e",
    "ATLAS_REPOSITORY_ROOT=/no-repository-mounted",
    imageId,
  ]);
  const state = owned(resource);
  assert.deepEqual(
    Object.keys(state.NetworkSettings.Networks),
    [network.name],
    "API must use only its internal drill network",
  );
  assert.equal(
    state.HostConfig.Binds?.length ?? 0,
    0,
    "No workspace or existing volume mounts",
  );
  assert.equal(
    Object.keys(state.HostConfig.PortBindings ?? {}).length,
    0,
    "Isolated API has no published ports",
  );
  function request(path, method = "GET", body, key) {
    owned(resource);
    assert.ok(
      path.startsWith("/") && !path.includes("..") && !/[\r\n]/.test(path),
      "Fixed local API path",
    );
    const payload = body ? JSON.stringify(body) : "";
    // HTTP/1.0 + close avoids chunked framing. nc receives literal stdin, never a shell command.
    const wire = `${method} ${path} HTTP/1.0\r\nHost: 127.0.0.1:8080\r\nConnection: close\r\nAccept: application/json\r\nAuthorization: Bearer ${token}\r\nContent-Length: ${Buffer.byteLength(payload)}\r\n${body ? "Content-Type: application/json\r\n" : ""}${key ? `Idempotency-Key: ${key}\r\n` : ""}\r\n${payload}`;
    return parseLocalHttp(
      docker(
        ["exec", "-i", resource.name, "nc", "-w", "20", "127.0.0.1", "8080"],
        Buffer.from(wire),
        true,
      ),
    );
  }
  await waitFor(
    () => request("/actuator/health").status === 200,
    "Isolated API",
    150,
  );
  return async function api(path, method = "GET", body, key) {
    const response = request(`/api${path}`, method, body, key);
    assert.ok(
      response.status >= 200 && response.status < 300,
      `Generated API workflow returned HTTP ${response.status}`,
    );
    return JSON.parse(response.body.toString("utf8"));
  };
}

function tableFacts(database) {
  const tables = sql(
    database,
    "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;",
  )
    .split("\n")
    .filter(Boolean);
  const facts = {};
  for (const table of tables) {
    assert.match(table, /^[a-z_][a-z0-9_]*$/, "Generated schema identifier");
    facts[table] = JSON.parse(
      sql(
        database,
        `SELECT json_build_object('rows',count(*),'fingerprint',md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),''))) FROM public."${table}" t;`,
      ),
    );
  }
  const sequences = sql(
    database,
    "SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename;",
  )
    .split("\n")
    .filter(Boolean);
  for (const sequence of sequences) {
    assert.match(
      sequence,
      /^[a-z_][a-z0-9_]*$/,
      "Generated sequence identifier",
    );
    facts[`sequence:${sequence}`] = JSON.parse(
      sql(
        database,
        `SELECT json_build_object('last_value',last_value,'is_called',is_called) FROM public."${sequence}";`,
      ),
    );
  }
  return facts;
}

async function capturedState(api, fixture) {
  return {
    payment: await api(`/payments/${fixture.paymentId}`),
    reconciliation: await api("/ledger/reconciliation"),
    event: await api(`/events/${fixture.eventId}`),
    worker: await api("/worker"),
    documents: await api("/workspace/documents"),
    journalRevisions: await api(
      `/workspace/documents/${fixture.journalId}/revisions`,
    ),
    diagramRevisions: await api(
      `/workspace/documents/${fixture.diagramId}/revisions`,
    ),
    adrRevisions: await api(
      `/workspace/documents/${fixture.adrId}/revisions`,
    ),
    incidentEvidence: await api(
      `/observability/incidents/${fixture.incidentId}/evidence`,
    ),
    incidentSummary: await api(
      `/assistant/incidents/${fixture.incidentId}/summary`,
    ),
  };
}

async function seed(api) {
  await api("/worker", "PUT", { running: false });
  const authorizationKey = randomUUID(),
    captureKey = randomUUID();
  const authorization = {
    merchantId: "merchant_demo",
    amountMinor: 12500,
    currency: "USD",
  };
  const authorized = await api(
    "/payments",
    "POST",
    authorization,
    authorizationKey,
  );
  assert.equal(authorized.payment.status, "AUTHORIZED");
  const paymentId = authorized.payment.id;
  const captured = await api(
    `/payments/${paymentId}/capture`,
    "POST",
    undefined,
    captureKey,
  );
  const refunded = await api(
    `/payments/${paymentId}/refund`,
    "POST",
    { amountMinor: 2500 },
    randomUUID(),
  );
  assert.equal(refunded.payment.refundedMinor, 2500);
  const experiment = await api(
    "/experiments",
    "POST",
    { scenario: "CRASH_AFTER_COMMIT_ONCE" },
    randomUUID(),
  );
  const eventId = experiment.eventId;
  const step = await api("/worker/step", "POST", { eventId, advanceTicks: 1 });
  assert.equal(step.outcome, "CRASH_AFTER_COMMIT");
  const hook = await api(`/webhooks/demo/${eventId}`, "POST");
  assert.equal(hook.effects, 1);
  const adrId = randomUUID();
  const adrBody = {
    Context: "Generated workspace needs a reproducible recovery exercise.",
    Decision: "Restore only into a fresh isolated database.",
    Alternatives: "In-place restore was considered and rejected.",
    Tradeoffs: "An extra API startup costs time but verifies application readback.",
    Consequences: "Existing user data remains untouched.",
    "Review date": "2027-01-23",
  };
  for (const version of [0, 1]) {
    await api(`/workspace/documents/${adrId}`, "PUT", {
      kind: "ADR",
      title: "Generated backup recovery decision",
      expectedVersion: version,
      body: {
        ...adrBody,
        Consequences:
          version === 0
            ? adrBody.Consequences
            : "Existing user data remains untouched; verify cleanup after failure.",
      },
    });
  }
  const journalId = randomUUID(),
    diagramId = randomUUID();
  await api(`/workspace/documents/${journalId}`, "PUT", {
    kind: "JOURNAL",
    title: "Generated backup journal",
    expectedVersion: 0,
    body: {
      purpose: "Generated recovery exercise",
      evidence: "Synthetic first revision",
    },
  });
  await api(`/workspace/documents/${journalId}`, "PUT", {
    kind: "JOURNAL",
    title: "Generated backup journal",
    expectedVersion: 1,
    body: {
      purpose: "Generated recovery exercise",
      evidence: "Synthetic second revision · €",
    },
  });
  await api(`/workspace/documents/${diagramId}`, "PUT", {
    kind: "DIAGRAM",
    title: "Generated recovery architecture",
    expectedVersion: 0,
    body: {
      assumptions: "Synthetic local workspace; no production capacity claim",
      nodes: [
        {
          id: "api",
          name: "API",
          type: "service",
          responsibility: "Validate generated requests",
          x: 50,
          y: 80,
          replicas: 1,
        },
        {
          id: "db",
          name: "PostgreSQL",
          type: "database",
          responsibility: "Keep generated durable state",
          x: 300,
          y: 80,
          replicas: 1,
        },
      ],
      edges: [
        { id: "sql", from: "api", to: "db", pattern: "Transactional SQL" },
      ],
    },
  });
  const incident = await api("/observability/incidents", "POST", {
    scenario: "SLOW_QUERY",
  });
  const fixture = {
    paymentId,
    eventId,
    journalId,
    diagramId,
    adrId,
    incidentId: incident.id,
    authorization,
    authorizationKey,
    captureKey,
    authorized,
    captured,
  };
  const state = await capturedState(api, fixture);
  assert.equal(state.adrRevisions.length, 2);
  assert.equal(state.incidentSummary.id, incident.id);
  assert.equal(state.incidentSummary.mode, "DETERMINISTIC_SAVED_EVIDENCE");
  assert.equal(state.incidentSummary.evidenceAvailable, true);
  assert.equal(state.incidentSummary.baseline.measuredSamples, 3);
  assert.equal(state.incidentSummary.affected.measuredSamples, 3);
  const retainedTraceIds = new Set(
    state.incidentEvidence.spans.map((span) => span.traceId),
  );
  const spanCitations = state.incidentSummary.findings
    .flatMap((finding) => finding.citations)
    .filter((citation) => citation.spanId);
  assert.ok(spanCitations.length > 0, "Generated summary must cite retained spans");
  for (const citation of spanCitations) {
    assert.ok(retainedTraceIds.has(citation.traceId));
    assert.ok(
      state.incidentEvidence.spans.some(
        (span) =>
          span.traceId === citation.traceId && span.spanId === citation.spanId,
      ),
    );
  }
  assert.equal(state.payment.postings.length, 2);
  assert.ok(state.reconciliation.every((row) => row.balanced));
  const dollars = state.reconciliation.find((row) => row.currency === "USD");
  assert.equal(dollars?.debitsMinor, 15000);
  assert.equal(dollars?.creditsMinor, 15000);
  assert.equal(dollars?.postingCount, 2);
  assert.equal(state.event.event.effects, 1);
  assert.equal(state.event.event.status, "PROCESSING");
  assert.equal(state.journalRevisions.length, 2);
  assert.ok(
    state.incidentEvidence.spans.some((span) => span.name === "db.query"),
  );
  return { fixture, state };
}

async function main() {
  // Inspect existing images only. No pull/build, environment file, host mount or external service.
  const apiImageId = docker([
    "image",
    "inspect",
    "--format",
    "{{.Id}}",
    apiImage,
  ]).trim();
  const postgresImageId = docker([
    "image",
    "inspect",
    "--format",
    "{{.Id}}",
    postgresImage,
  ]).trim();
  assert.match(apiImageId, /^sha256:[a-f0-9]{64}$/);
  assert.match(postgresImageId, /^sha256:[a-f0-9]{64}$/);
  let result;
  let primaryFailure;
  try {
    phase = "resources";
    resources.push(network);
    docker(["network", "create", "--internal", ...labels(), network.name]);
    owned(network);
    resources.push(db);
    docker([
      "run",
      "--detach",
      "--pull=never",
      "--name",
      db.name,
      ...labels(),
      "--network",
      network.name,
      "--memory",
      "256m",
      "--cpus",
      "1",
      "--pids-limit",
      "128",
      "--tmpfs",
      "/var/lib/postgresql/data:rw,size=192m",
      "-e",
      `POSTGRES_DB=${sourceDatabase}`,
      "-e",
      "POSTGRES_USER=atlas",
      "-e",
      `POSTGRES_PASSWORD=${password}`,
      postgresImageId,
    ]);
    const databaseState = owned(db);
    assert.deepEqual(
      Object.keys(databaseState.NetworkSettings.Networks),
      [network.name],
      "Database must use only its internal drill network",
    );
    assert.equal(
      databaseState.HostConfig.Binds?.length ?? 0,
      0,
      "No existing database volumes",
    );
    assert.equal(
      Object.keys(databaseState.HostConfig.PortBindings ?? {}).length,
      0,
      "Database has no published ports",
    );
    await waitFor(() => {
      docker([
        "exec",
        db.name,
        "pg_isready",
        "-h",
        "127.0.0.1",
        "-U",
        "atlas",
        "-d",
        sourceDatabase,
      ]);
      return true;
    }, "Isolated PostgreSQL");
    if (failureCheck) throw new Error("EXPECTED_GENERATED_FAILURE");
    phase = "seeding";
    console.log("Seeding a new isolated ATLAS workspace through its API.");
    const sourceApi = await startApi(source, sourceDatabase, apiImageId);
    const { fixture, state } = await seed(sourceApi);
    owned(source);
    docker(["stop", "--time", "15", source.name]);
    // Only this generated API is stopped. No source writer remains during fingerprint/dump.
    phase = "backup";
    const before = tableFacts(sourceDatabase);
    const flyway = sql(
      sourceDatabase,
      "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL;",
    );
    assert.equal(Number(flyway), 5, "Expected current five Flyway migrations");
    const bytes = docker(
      [
        "exec",
        db.name,
        "pg_dump",
        "-U",
        "atlas",
        "-d",
        sourceDatabase,
        "--format=custom",
        "--no-owner",
        "--no-acl",
      ],
      undefined,
      true,
    );
    const runtime = join(root, ".runtime");
    await mkdir(runtime, { recursive: true });
    outputDirectory = join(runtime, `application-backup-${runId}`);
    await mkdir(outputDirectory, { mode: 0o700 });
    const resolved = await realpath(outputDirectory),
      resolvedRuntime = await realpath(runtime);
    const within = relative(resolvedRuntime, resolved);
    assert.ok(
      within && !within.startsWith("..") && !isAbsolute(within),
      "Backup output must stay in this run's ignored runtime folder",
    );
    const manifest = await writeBackup(outputDirectory, bytes, {
      runId,
      apiImageId,
      postgresImageId,
    });
    const verified = await verifyBackup(outputDirectory, runId);
    assert.equal(verified.bytes.length, bytes.length);
    assert.equal(verified.manifest.dump.sha256, manifest.dump.sha256);
    phase = "restore";
    assert.equal(
      sql(
        sourceDatabase,
        `SELECT count(*) FROM pg_database WHERE datname='${restoredDatabase}';`,
      ),
      "0",
      "Restore database must not exist",
    );
    owned(db);
    docker(["exec", db.name, "createdb", "-U", "atlas", restoredDatabase]);
    assert.equal(
      sql(
        restoredDatabase,
        "SELECT count(*) FROM pg_tables WHERE schemaname='public';",
      ),
      "0",
      "Restore target must be empty",
    );
    docker(
      [
        "exec",
        "-i",
        db.name,
        "pg_restore",
        "-U",
        "atlas",
        "--dbname",
        restoredDatabase,
        "--single-transaction",
        "--exit-on-error",
        "--no-owner",
        "--no-acl",
      ],
      verified.bytes,
    );
    assert.deepEqual(
      tableFacts(restoredDatabase),
      before,
      "Restored rows, Flyway history and sequence states differ",
    );
    console.log(
      "Opening the restored generated workspace in a fresh ATLAS process.",
    );
    phase = "readback";
    const restoredApi = await startApi(restored, restoredDatabase, apiImageId);
    assert.deepEqual(
      await capturedState(restoredApi, fixture),
      state,
      "Application readback differs after restore",
    );
    assert.deepEqual(
      tableFacts(restoredDatabase),
      before,
      "Startup/readback changed restored durable state",
    );
    const replay = await restoredApi(
      "/payments",
      "POST",
      fixture.authorization,
      fixture.authorizationKey,
    );
    assert.equal(replay.replayed, true);
    assert.deepEqual(replay.payment, fixture.authorized.payment);
    const captureReplay = await restoredApi(
      `/payments/${fixture.paymentId}/capture`,
      "POST",
      undefined,
      fixture.captureKey,
    );
    assert.equal(captureReplay.replayed, true);
    assert.deepEqual(captureReplay.payment, fixture.captured.payment);
    assert.deepEqual(
      tableFacts(restoredDatabase),
      before,
      "Idempotent replay changed restored durable state",
    );
    phase = "recovery";
    const recovered = await restoredApi("/worker/step", "POST", {
      eventId: fixture.eventId,
      advanceTicks: 2,
    });
    assert.equal(recovered.outcome, "DELIVERED");
    const delivered = await restoredApi(`/events/${fixture.eventId}`);
    assert.equal(delivered.event.effects, 1);
    assert.equal(delivered.event.duplicatesSuppressed, 1);
    const hook = await restoredApi(`/webhooks/demo/${fixture.eventId}`, "POST");
    assert.equal(hook.effects, 1);
    sql(
      restoredDatabase,
      `DO $drill$
      BEGIN
        INSERT INTO ledger_posting(id,payment_id,kind,debit_account,credit_account,amount_minor,currency)
          VALUES('${randomUUID()}','${fixture.paymentId}','CAPTURE','acquirer_receivable','merchant_payable',12500,'USD');
        RAISE EXCEPTION 'Duplicate capture was unexpectedly accepted';
      EXCEPTION WHEN unique_violation THEN
        IF SQLERRM NOT LIKE '%one_capture_per_payment%' THEN RAISE; END IF;
      END $drill$;`,
    );
    const auditMaximum = Number(
      sql(restoredDatabase, "SELECT max(sequence) FROM audit_event;"),
    );
    await restoredApi(
      "/payments",
      "POST",
      { merchantId: "merchant_demo", amountMinor: 99, currency: "EUR" },
      randomUUID(),
    );
    assert.ok(
      Number(sql(restoredDatabase, "SELECT max(sequence) FROM audit_event;")) >
        auditMaximum,
      "Restored identity sequence must advance",
    );
    await restoredApi(`/workspace/documents/${fixture.journalId}`, "DELETE");
    assert.equal(
      (await restoredApi(`/workspace/documents/${fixture.journalId}/revisions`))
        .length,
      0,
      "Restored journal revisions must cascade",
    );
    await restoredApi(`/workspace/documents/${fixture.incidentId}`, "DELETE");
    assert.equal(
      sql(
        restoredDatabase,
        `SELECT count(*) FROM incident_evidence WHERE incident_id='${fixture.incidentId}';`,
      ),
      "0",
      "Restored incident evidence must cascade",
    );
    result = {
      result: "PASS",
      runId,
      backupDirectory: relative(root, outputDirectory).replaceAll("\\", "/"),
      dumpBytes: manifest.dump.bytes,
      sha256: manifest.dump.sha256,
      flywayMigrations: Number(flyway),
      verified: [
        "real API-created generated workspace",
        "custom dump and SHA-256",
        "restored Flyway history and startup",
        "all public-table row fingerprints and sequences",
        "capture/refund ledger readback",
        "journal revisions and diagram",
        "identical archived incident evidence",
        "identical recomputed incident summary and retained trace/span citations",
        "complete ADR and both revision bodies",
        "immutable idempotency replays",
        "commit-before-ack recovery without duplicate effect",
        "signed webhook receipt deduplication",
        "unique capture constraint, identity advancement and revision/evidence cascades",
      ],
      scope:
        "Generated isolated workspace only; no existing application/test database, journal or volume accessed. No in-place restore, external AI, encryption, retention, power-loss or production RPO/RTO claim.",
    };
  } catch (error) {
    primaryFailure = error;
  } finally {
    const operationPhase = phase;
    phase = "cleanup";
    cleanupOwned(resources, runId, inspect, remove);
    phase = operationPhase;
  }
  if (
    failureCheck &&
    primaryFailure?.message === "EXPECTED_GENERATED_FAILURE"
  ) {
    result = {
      result: "PASS",
      check:
        "Injected failure after disposable database startup; owned database and internal network removed",
      runId,
      scope: "Generated resources only; no API or existing workspace accessed",
    };
  } else if (primaryFailure) throw primaryFailure;
  assert.ok(result, "Drill must produce an explicit result");
  console.log(JSON.stringify({ ...result, cleanupVerified: true }, null, 2));
}

main().catch((error) => {
  console.error(failureSummary(error, phase));
  console.error(`Generated drill run ID: ${runId}`);
  process.exitCode = 1;
});
