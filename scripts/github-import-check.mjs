// Explicit operator smoke check: contacts public GitHub through the running local ATLAS API.
// Replaces the in-memory repository snapshot; never starts imported code or invokes a model.
import assert from "node:assert/strict";

const token = process.env.ATLAS_API_TOKEN;
if (!token) throw new Error("Set ATLAS_API_TOKEN for the local API.");
const repositories = process.argv.slice(2);
if (!repositories.length)
  throw new Error(
    "Pass one or more public https://github.com/owner/repository URLs.",
  );

async function api(path, body) {
  const response = await fetch(`http://127.0.0.1:8080/api${path}`, {
    method: body ? "POST" : "GET",
    redirect: "error",
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(95000),
  });
  const result = await response.json();
  assert.ok(
    response.ok,
    `${response.status}: ${result.code ?? "request failed"} ${result.message ?? ""}`,
  );
  return result;
}

for (const url of repositories) {
  const snapshot = await api("/repository/github", {
    url,
    directory: "",
    approved: true,
  });
  assert.equal(snapshot.origin.kind, "GITHUB");
  assert.match(snapshot.origin.commit, /^[a-f0-9]{40}$/);
  assert.ok(snapshot.files.length > 0 && snapshot.files.length <= 500);
  assert.ok(snapshot.bytes <= 4000000);
  const file =
    snapshot.files.find((item) => item.path.endsWith(".java")) ??
    snapshot.files[0];
  const source = await api(
    `/repository/file?path=${encodeURIComponent(file.path)}`,
  );
  assert.equal(source.content, file.content);
  const git = await api(
    `/repository/git?path=${encodeURIComponent(file.path)}`,
  );
  assert.ok(git.history.includes(snapshot.origin.commit));
  assert.ok(git.changes.includes("no local working-tree diff"));
  const graph = await api("/repository/architecture");
  assert.equal(graph.snapshotId, snapshot.id);
  const metadata = await api("/repository/metadata");
  if (snapshot.files.some((item) => item.language === "java"))
    assert.ok(graph.types.length > 0);
  const impact = await api("/repository/impact", {
    snapshotId: snapshot.id,
    paths: [file.path],
  });
  assert.equal(impact.snapshotId, snapshot.id);
  assert.deepEqual(impact.selectedPaths, [file.path]);
  console.log(
    JSON.stringify(
      {
        repository: snapshot.origin.repositoryUrl,
        commit: snapshot.origin.commit,
        files: snapshot.files.length,
        bytes: snapshot.bytes,
        languages: snapshot.languages,
        resolvedJavaTypes: graph.types.length,
        declarationReferences: graph.relationships.length,
        manifestDependencies: metadata.dependencies.length,
        sourceReadVerified: true,
        archiveProvenanceVerified: true,
        impactSnapshotVerified: true,
        scope:
          "Actual anonymous GitHub source download and local static analysis; no imported execution or model call.",
      },
      null,
      2,
    ),
  );
}
