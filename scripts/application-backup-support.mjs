// Bounded local backup primitives. These helpers never select a database or run Docker.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { lstat, open, unlink } from "node:fs/promises";
import { constants } from "node:fs";
import { join } from "node:path";

export const MAX_DUMP_BYTES = 64 * 1024 * 1024;
export const PURPOSE = "application-backup-drill";
const DUMP = "generated-workspace.dump";
const MANIFEST = "manifest.json";
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export function checksum(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

export function parseLocalHttp(bytes) {
  assert.ok(
    Buffer.isBuffer(bytes) && bytes.length <= 2 * 1024 * 1024,
    "Local HTTP response byte budget",
  );
  const boundary = bytes.indexOf("\r\n\r\n");
  assert.ok(boundary >= 0 && boundary <= 16384, "Local HTTP header budget");
  const lines = bytes.subarray(0, boundary).toString("ascii").split("\r\n");
  const status = lines.shift().match(/^HTTP\/1\.[01] ([2-5][0-9]{2})(?: |$)/);
  assert.ok(status, "Expected one final HTTP/1.x response");
  const headers = new Map();
  for (const line of lines) {
    const colon = line.indexOf(":");
    assert.ok(colon > 0, "Malformed local HTTP header");
    const name = line.slice(0, colon).toLowerCase(),
      value = line.slice(colon + 1).trim();
    if (name === "content-length" || name === "transfer-encoding")
      assert.ok(!headers.has(name), "Ambiguous local HTTP framing");
    headers.set(name, value);
  }
  assert.ok(
    !headers.has("transfer-encoding"),
    "HTTP/1.0 drill requests require an unchunked response",
  );
  const body = bytes.subarray(boundary + 4);
  if (headers.has("content-length")) {
    assert.match(
      headers.get("content-length"),
      /^[0-9]+$/,
      "Local HTTP content length",
    );
    assert.equal(
      body.length,
      Number(headers.get("content-length")),
      "Truncated local HTTP response",
    );
  }
  return { status: Number(status[1]), body };
}

async function readBoundedFile(path, maximum) {
  const info = await lstat(path);
  assert.ok(
    info.isFile() && !info.isSymbolicLink(),
    "Backup input must be a regular file",
  );
  assert.ok(
    info.size > 0 && info.size <= maximum,
    "Backup file exceeds its byte budget or is empty",
  );
  const handle = await open(
    path,
    constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0),
  );
  try {
    const opened = await handle.stat();
    assert.ok(
      opened.isFile() && opened.size > 0 && opened.size <= maximum,
      "Opened backup file byte budget",
    );
    assert.ok(
      opened.dev === info.dev && opened.ino === info.ino,
      "Backup file changed while opening",
    );
    const bytes = Buffer.alloc(opened.size + 1);
    let total = 0;
    while (total < bytes.length) {
      const { bytesRead } = await handle.read(
        bytes,
        total,
        bytes.length - total,
        null,
      );
      if (!bytesRead) break;
      total += bytesRead;
    }
    assert.equal(total, opened.size, "Backup file length changed during read");
    return bytes.subarray(0, total);
  } finally {
    await handle.close();
  }
}

export async function writeBackup(directory, bytes, metadata) {
  assert.ok(
    Buffer.isBuffer(bytes) &&
      bytes.length > 0 &&
      bytes.length <= MAX_DUMP_BYTES,
    "Backup byte budget",
  );
  assert.match(metadata.runId, UUID, "Backup run ID");
  assert.ok(
    bytes.subarray(0, 5).equals(Buffer.from("PGDMP")),
    "Custom-format PostgreSQL dump required",
  );
  const manifest = {
    formatVersion: 1,
    purpose: PURPOSE,
    scope:
      "Generated isolated ATLAS workspace only; no existing application or test database",
    runId: metadata.runId,
    createdAt: new Date().toISOString(),
    apiImageId: metadata.apiImageId,
    postgresImageId: metadata.postgresImageId,
    dump: { name: DUMP, bytes: bytes.length, sha256: checksum(bytes) },
  };
  const created = [];
  try {
    for (const [name, content] of [
      [DUMP, bytes],
      [MANIFEST, Buffer.from(JSON.stringify(manifest, null, 2) + "\n")],
    ]) {
      const path = join(directory, name);
      const file = await open(path, "wx", 0o600);
      created.push(path);
      try {
        await file.writeFile(content);
        await file.sync();
      } finally {
        await file.close();
      }
    }
    return manifest;
  } catch (error) {
    // Only paths opened exclusively by this invocation can be removed.
    for (const path of created.reverse()) await unlink(path).catch(() => {});
    throw error;
  }
}

export async function verifyBackup(directory, runId) {
  assert.match(runId, UUID, "Backup run ID");
  const manifestPath = join(directory, MANIFEST);
  const manifest = JSON.parse(
    (await readBoundedFile(manifestPath, 16384)).toString("utf8"),
  );
  assert.equal(manifest.formatVersion, 1, "Backup manifest version");
  assert.equal(manifest.purpose, PURPOSE, "Backup purpose");
  assert.equal(manifest.runId, runId, "Backup ownership");
  assert.equal(manifest.dump?.name, DUMP, "Backup filename allowlist");
  assert.ok(
    Number.isInteger(manifest.dump.bytes) &&
      manifest.dump.bytes > 0 &&
      manifest.dump.bytes <= MAX_DUMP_BYTES,
    "Manifest byte budget",
  );
  assert.match(manifest.dump.sha256, /^[a-f0-9]{64}$/, "Manifest checksum");
  const dumpPath = join(directory, DUMP);
  const bytes = await readBoundedFile(dumpPath, MAX_DUMP_BYTES);
  assert.equal(bytes.length, manifest.dump.bytes, "Backup byte length changed");
  assert.equal(
    checksum(bytes),
    manifest.dump.sha256,
    "Backup SHA-256 mismatch",
  );
  assert.ok(
    bytes.subarray(0, 5).equals(Buffer.from("PGDMP")),
    "Custom-format PostgreSQL dump required",
  );
  return { manifest, bytes };
}

export function assertOwned(resource, inspection, runId) {
  assert.match(runId, UUID, "Resource run ID");
  assert.ok(
    ["container", "network"].includes(resource.kind),
    "Resource kind allowlist",
  );
  const suffixes =
    resource.kind === "network" ? ["net"] : ["db", "source", "restored"];
  assert.ok(
    suffixes.some(
      (suffix) => resource.name === `atlas-app-backup-${runId}-${suffix}`,
    ),
    "Resource name ownership",
  );
  assert.equal(
    inspection.Name.replace(/^\//, ""),
    resource.name,
    "Inspected resource name",
  );
  const labels =
    resource.kind === "network" ? inspection.Labels : inspection.Config?.Labels;
  assert.equal(
    labels?.["dev.atlas.purpose"],
    PURPOSE,
    "Resource purpose label",
  );
  assert.equal(labels?.["dev.atlas.run"], runId, "Resource run label");
  if (resource.kind === "network")
    assert.equal(inspection.Internal, true, "Internal-only network required");
}

export function cleanupOwned(resources, runId, inspect, remove) {
  const failed = [];
  for (const resource of [...resources].reverse()) {
    try {
      const inspection = inspect(resource);
      if (inspection === null) continue;
      assertOwned(resource, inspection, runId);
      remove(resource);
      assert.equal(
        inspect(resource),
        null,
        "Owned resource remains after cleanup",
      );
    } catch {
      failed.push(resource.name);
    }
  }
  assert.equal(
    failed.length,
    0,
    `Cleanup refused or failed for ${failed.length} resource(s); inspect this run's ownership labels`,
  );
}

export function failureSummary(error, phase) {
  const phases = new Set([
    "preflight",
    "resources",
    "seeding",
    "backup",
    "restore",
    "readback",
    "recovery",
    "cleanup",
  ]);
  const category = error?.code === "ERR_ASSERTION" ? "validation" : "operation";
  const location = error?.stack?.match(
    /application-backup-drill\.mjs:(\d+):(\d+)/,
  );
  const line = location ? ` at script line ${location[1]}` : "";
  return `Application backup drill failed during ${phases.has(phase) ? phase : "unknown"} (${category})${line}; payload details were withheld.`;
}
