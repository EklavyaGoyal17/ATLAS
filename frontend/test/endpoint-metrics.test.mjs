import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import { test } from "node:test";
import { transformWithOxc } from "vite";

const source = new URL("../src/EndpointMetrics.tsx", import.meta.url);
const runtime = pathToFileURL(
  createRequire(import.meta.url).resolve("react/jsx-runtime"),
).href;
const transformed = await transformWithOxc(
  await readFile(source, "utf8"),
  fileURLToPath(source),
  {
    jsx: { runtime: "automatic" },
    sourcemap: false,
  },
);
const code = transformed.code.replace(
  /(["'])react\/jsx-runtime\1/g,
  JSON.stringify(runtime),
);
const { EndpointMetrics } = await import(
  `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`
);

function elements(tree) {
  if (Array.isArray(tree)) return tree.flatMap(elements);
  if (!tree || typeof tree !== "object" || !tree.props) return [];
  return [tree, ...elements(tree.props.children)];
}
function text(tree) {
  if (Array.isArray(tree)) return tree.map(text).join("");
  if (tree && typeof tree === "object") return text(tree.props?.children);
  return tree == null ? "" : String(tree);
}
const defaults = {
  endpointLimits: {
    operations: 92,
    samplesPerOperation: 100,
    totalSamples: 9200,
  },
  endpointScope: "Lifetime counters; retained sample windows only.",
};
function row(operation, p95Ms) {
  return {
    operation,
    requests: 150,
    errors: 1,
    errorRate: 1 / 150,
    latencySamples: 100,
    p50Ms: 10,
    p95Ms,
    p99Ms: 99,
    windowStart: "2026-10-22T01:00:00Z",
    windowEnd: "2026-10-22T01:02:00Z",
  };
}

test("operation comparison displays actual supplied counts, windows and descending p95 without mutating data", () => {
  const endpoints = [
    row("HTTP POST /api/payments", 40),
    row("HTTP POST /api/payments/{id}/refund", 80),
  ];
  const tree = EndpointMetrics({ ...defaults, endpoints });
  const nodes = elements(tree);
  const operationCells = nodes.filter(
    (node) => node.type === "th" && node.props.scope === "row",
  );
  assert.deepEqual(operationCells.map(text), [
    endpoints[1].operation,
    endpoints[0].operation,
  ]);
  assert.equal(endpoints[0].p95Ms, 40);
  assert.equal(
    nodes.find((node) => node.props.role === "region").props.tabIndex,
    0,
  );
  assert.match(text(tree), /150/);
  assert.match(text(tree), /1 \(0\.7%\)/);
  assert.match(text(tree), /10\.00 \/ 80\.00 \/ 99\.00/);
  assert.match(text(tree), /100 latency samples each \(9200 total\)/);
  assert.match(text(tree), /not controlled performance comparisons/);
  assert.deepEqual(
    nodes
      .filter((node) => node.type === "time")
      .map((node) => node.props.dateTime),
    [
      endpoints[1].windowStart,
      endpoints[1].windowEnd,
      endpoints[0].windowStart,
      endpoints[0].windowEnd,
    ],
  );
});

test("empty operation telemetry states that no samples exist without fabricating zero-valued measurements", () => {
  const tree = EndpointMetrics({ ...defaults, endpoints: [] });
  assert.match(
    text(tree),
    /No authenticated operation samples in this JVM yet/,
  );
  assert.equal(
    elements(tree).filter((node) => node.type === "table").length,
    0,
  );
});
