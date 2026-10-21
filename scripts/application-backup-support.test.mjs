import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import {
  mkdtemp,
  readFile,
  writeFile,
  readdir,
  unlink,
  rmdir,
  symlink,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import {
  writeBackup,
  verifyBackup,
  assertOwned,
  cleanupOwned,
  checksum,
  MAX_DUMP_BYTES,
  PURPOSE,
  failureSummary,
  parseLocalHttp,
} from "./application-backup-support.mjs";

const dump = Buffer.from("PGDMP\u0001generated-only-backup-fixture");
const metadata = () => ({
  runId: randomUUID(),
  apiImageId: "sha256:fixture-api",
  postgresImageId: "sha256:fixture-pg",
});

async function directory(t) {
  const path = await mkdtemp(join(tmpdir(), "atlas-backup-support-"));
  t.after(async () => {
    // Test owns exactly this freshly created temp directory; no recursive deletion.
    for (const name of await readdir(path)) await unlink(join(path, name));
    await rmdir(path);
  });
  return path;
}

test("custom-format backup round trip validates bytes, SHA256 and run ownership", async (t) => {
  const path = await directory(t),
    info = metadata();
  const manifest = await writeBackup(path, dump, info);
  const verified = await verifyBackup(path, info.runId);
  assert.deepEqual(verified.bytes, dump);
  assert.equal(manifest.dump.sha256, checksum(dump));
  assert.equal(verified.manifest.apiImageId, info.apiImageId);
  await assert.rejects(verifyBackup(path, randomUUID()), /ownership/);
});

test("refuses dump overwrite and preserves the existing bytes", async (t) => {
  const path = await directory(t),
    info = metadata();
  await writeBackup(path, dump, info);
  await assert.rejects(
    writeBackup(path, Buffer.concat([dump, Buffer.from("changed")]), info),
    { code: "EEXIST" },
  );
  assert.deepEqual(
    await readFile(join(path, "generated-workspace.dump")),
    dump,
  );
  await verifyBackup(path, info.runId);
});

test("manifest collision rolls back only the newly created dump", async (t) => {
  const path = await directory(t);
  await writeFile(join(path, "manifest.json"), "existing operator file", {
    flag: "wx",
  });
  await assert.rejects(writeBackup(path, dump, metadata()), { code: "EEXIST" });
  assert.deepEqual(await readdir(path), ["manifest.json"]);
  assert.equal(
    await readFile(join(path, "manifest.json"), "utf8"),
    "existing operator file",
  );
});

test("rejects modified and truncated dumps before any restore", async (t) => {
  const path = await directory(t),
    info = metadata();
  await writeBackup(path, dump, info);
  const changed = Buffer.from(dump);
  changed[changed.length - 1] ^= 1;
  await writeFile(join(path, "generated-workspace.dump"), changed);
  await assert.rejects(verifyBackup(path, info.runId), /SHA-256/);
  await writeFile(join(path, "generated-workspace.dump"), dump.subarray(0, 8));
  await assert.rejects(verifyBackup(path, info.runId), /length changed/);
});

test("rejects noncustom or oversized inputs without creating output files", async (t) => {
  const path = await directory(t);
  await assert.rejects(
    writeBackup(path, Buffer.from("SQL text"), metadata()),
    /Custom-format/,
  );
  await assert.rejects(
    writeBackup(path, Buffer.alloc(MAX_DUMP_BYTES + 1), metadata()),
    /byte budget/,
  );
  assert.deepEqual(await readdir(path), []);
});

test("manifest cannot select another filename or bypass byte bounds", async (t) => {
  const path = await directory(t),
    info = metadata();
  const manifest = await writeBackup(path, dump, info);
  manifest.dump.name = "../other.dump";
  await writeFile(join(path, "manifest.json"), JSON.stringify(manifest));
  await assert.rejects(verifyBackup(path, info.runId), /filename allowlist/);
  manifest.dump.name = "generated-workspace.dump";
  manifest.dump.bytes = MAX_DUMP_BYTES + 1;
  await writeFile(join(path, "manifest.json"), JSON.stringify(manifest));
  await assert.rejects(verifyBackup(path, info.runId), /byte budget/);
});

function resource(runId, suffix = "db", kind = "container") {
  const item = { kind, name: `atlas-app-backup-${runId}-${suffix}` };
  const labels = { "dev.atlas.purpose": PURPOSE, "dev.atlas.run": runId };
  return [
    item,
    kind === "network"
      ? { Name: item.name, Labels: labels, Internal: true }
      : { Name: "/" + item.name, Config: { Labels: labels } },
  ];
}

test("cleanup requires exact names, both ownership labels and internal network", () => {
  const runId = randomUUID();
  for (const [item, inspection] of [
    resource(runId),
    resource(runId, "net", "network"),
  ]) {
    assert.doesNotThrow(() => assertOwned(item, inspection, runId));
    assert.throws(() => assertOwned(item, inspection, randomUUID()));
  }
  const [network, inspection] = resource(runId, "net", "network");
  inspection.Internal = false;
  assert.throws(() => assertOwned(network, inspection, runId));
  const [container, state] = resource(runId);
  delete state.Config.Labels["dev.atlas.purpose"];
  assert.throws(() => assertOwned(container, state, runId));
});

test("failed-path cleanup removes owned resources in reverse order and refuses unrelated targets", () => {
  const runId = randomUUID();
  const entries = [
    resource(runId, "net", "network"),
    resource(runId),
    resource(runId, "source"),
  ];
  const states = new Map(entries.map(([item, state]) => [item.name, state])),
    removed = [];
  cleanupOwned(
    entries.map(([item]) => item),
    runId,
    (item) => states.get(item.name) ?? null,
    (item) => {
      removed.push(item.name);
      states.delete(item.name);
    },
  );
  assert.deepEqual(removed, entries.map(([item]) => item.name).reverse());
  const unrelated = { kind: "container", name: "atlas-db-1" };
  assert.throws(() =>
    cleanupOwned(
      [unrelated],
      runId,
      () => ({ Name: "/atlas-db-1", Config: { Labels: {} } }),
      () => assert.fail("Must not remove existing database"),
    ),
  );
});

test("cleanup continues with other owned resources after a refused resource", () => {
  const runId = randomUUID(),
    [db, state] = resource(runId),
    [api, wrong] = resource(runId, "source");
  wrong.Config.Labels["dev.atlas.run"] = randomUUID();
  let removed = false;
  assert.throws(() =>
    cleanupOwned(
      [db, api],
      runId,
      (item) => (item === db ? (removed ? null : state) : wrong),
      () => {
        removed = true;
      },
    ),
  );
  assert.equal(removed, true);
});

test("rejects dump symlinks instead of following a replacement path", async (t) => {
  const path = await directory(t),
    info = metadata();
  await writeBackup(path, dump, info);
  await unlink(join(path, "generated-workspace.dump"));
  await writeFile(join(path, "outside.dump"), dump);
  try {
    await symlink(
      join(path, "outside.dump"),
      join(path, "generated-workspace.dump"),
    );
  } catch (error) {
    if (error.code === "EPERM") {
      t.skip("Windows account cannot create file symlinks");
      return;
    }
    throw error;
  }
  await assert.rejects(verifyBackup(path, info.runId), /regular file/);
});

test("failure summaries exclude assertion diffs, parser snippets, command details and unknown phases", () => {
  const privateMarker = "must-never-appear-in-drill-output";
  const failures = [new SyntaxError(privateMarker), new Error(privateMarker)];
  try {
    assert.deepEqual(
      { body: privateMarker },
      { body: "different" },
      "State differs",
    );
  } catch (error) {
    failures.push(error);
  }
  for (const failure of failures) {
    assert.doesNotMatch(
      failureSummary(failure, "readback"),
      new RegExp(privateMarker),
    );
    assert.match(failureSummary(failure, "readback"), /during readback/);
    assert.doesNotMatch(
      failureSummary(failure, privateMarker),
      new RegExp(privateMarker),
    );
  }
});

test("bounded internal HTTP parser preserves UTF-8 JSON and validates response status/framing", () => {
  const payload = Buffer.from(JSON.stringify({ evidence: "Synthetic € 中文" }));
  const wire = Buffer.concat([
    Buffer.from(
      `HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.length}\r\n\r\n`,
    ),
    payload,
  ]);
  const result = parseLocalHttp(wire);
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, payload);
  assert.throws(
    () => parseLocalHttp(wire.subarray(0, wire.length - 1)),
    /Truncated/,
  );
  assert.throws(
    () =>
      parseLocalHttp(
        Buffer.from(
          "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
        ),
      ),
    /unchunked/,
  );
  assert.throws(
    () =>
      parseLocalHttp(
        Buffer.from(
          "HTTP/1.0 200 OK\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n",
        ),
      ),
    /Ambiguous/,
  );
  assert.throws(
    () => parseLocalHttp(Buffer.alloc(2 * 1024 * 1024 + 1)),
    /byte budget/,
  );
  assert.throws(
    () => parseLocalHttp(Buffer.from("unexpected non-HTTP output")),
    /header budget/,
  );
});
