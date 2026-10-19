// A bounded correctness/load observation. Writes synthetic payments only.
import { performance } from "node:perf_hooks";
import { randomUUID } from "node:crypto";
const base = process.env.ATLAS_URL ?? "http://127.0.0.1:8080";
const token = process.env.ATLAS_API_TOKEN;
if (!token) throw new Error("Set ATLAS_API_TOKEN");
const count = Number(process.env.ATLAS_REQUESTS ?? 25);
if (!Number.isInteger(count) || count < 1 || count > 100)
  throw new Error("ATLAS_REQUESTS must be 1–100");
const key = randomUUID();
const begin = performance.now();
const responses = await Promise.all(
  Array.from({ length: count }, async () => {
    const start = performance.now();
    const response = await fetch(`${base}/api/payments`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        "Idempotency-Key": key,
      },
      body: JSON.stringify({
        merchantId: "merchant_demo",
        amountMinor: 12500,
        currency: "USD",
      }),
      signal: AbortSignal.timeout(30000),
    });
    return {
      status: response.status,
      ms: performance.now() - start,
      body: await response.json(),
    };
  }),
);
const ids = new Set(responses.map((r) => r.body.payment?.id));
const durations = responses.map((r) => r.ms).sort((a, b) => a - b);
const result = {
  runtime: process.version,
  requests: count,
  elapsedMs: +(performance.now() - begin).toFixed(2),
  statusCounts: responses.reduce(
    (all, r) => ((all[r.status] = (all[r.status] ?? 0) + 1), all),
    {},
  ),
  distinctPaymentIds: ids.size,
  idempotencyKey: key,
  paymentId: responses[0].body.payment?.id,
  minMs: +durations[0].toFixed(2),
  maxMs: +durations.at(-1).toFixed(2),
  note: "One local duplicate burst, not a capacity benchmark. HTTP timing includes client connection overhead.",
};
console.log(JSON.stringify(result, null, 2));
if (
  ids.size !== 1 ||
  ids.has(undefined) ||
  responses.filter((r) => r.status === 201).length !== 1 ||
  responses.some((r) => ![200, 201].includes(r.status))
)
  process.exitCode = 1;
