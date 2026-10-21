import assert from "node:assert/strict";
import { test } from "node:test";
import { api } from "../src/api.ts";

const token = "synthetic-test-token";

function deadline(t) {
  const controller = new AbortController();
  const durations = [];
  t.mock.method(AbortSignal, "timeout", (duration) => {
    durations.push(duration);
    return controller.signal;
  });
  return { controller, durations };
}

test("ordinary reads keep a 15 second authenticated request deadline", async (t) => {
  const { controller, durations } = deadline(t);
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.equal(url, "/api/repository");
    assert.equal(options.method, "GET");
    assert.deepEqual(options.headers, { Authorization: `Bearer ${token}` });
    assert.equal(options.body, undefined);
    assert.equal(options.signal, controller.signal);
    return Response.json({ id: "approved-snapshot" });
  });
  assert.deepEqual(await api(token, "/repository"), {
    id: "approved-snapshot",
  });
  assert.deepEqual(durations, [15000]);
});

test("an approved GitHub POST can wait 95 seconds while preserving auth and payload", async (t) => {
  const { controller, durations } = deadline(t);
  const payload = {
    url: "https://github.com/example/project",
    directory: "src",
    approved: true,
  };
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.equal(url, "/api/repository/github");
    assert.equal(options.method, "POST");
    assert.deepEqual(options.headers, {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    });
    assert.deepEqual(JSON.parse(options.body), payload);
    assert.equal(options.signal, controller.signal);
    assert.equal(options.signal.aborted, false);
    return Response.json({ id: "github-snapshot" });
  });
  assert.deepEqual(
    await api(token, "/repository/github", {
      method: "POST",
      body: payload,
      timeoutMs: 95000,
    }),
    { id: "github-snapshot" },
  );
  assert.deepEqual(durations, [95000]);
});

test("local imports keep the ordinary deadline and idempotency headers still pass through", async (t) => {
  const { durations } = deadline(t);
  t.mock.method(globalThis, "fetch", async (_url, options) => {
    assert.equal(options.headers["Idempotency-Key"], "synthetic-request-key");
    assert.deepEqual(JSON.parse(options.body), { path: ".", approved: true });
    return Response.json({ id: "local-snapshot" });
  });
  await api(token, "/repository/import", {
    method: "POST",
    body: { path: ".", approved: true },
    key: "synthetic-request-key",
    timeoutMs: undefined,
  });
  assert.deepEqual(durations, [15000]);
});

test("deadline cancellation preserves the actual timeout error without a real wait", async (t) => {
  const { controller, durations } = deadline(t);
  t.mock.method(
    globalThis,
    "fetch",
    (_url, options) =>
      new Promise((_resolve, reject) => {
        options.signal.addEventListener(
          "abort",
          () => reject(options.signal.reason),
          {
            once: true,
          },
        );
      }),
  );
  const result = api(token, "/repository/github", { timeoutMs: 95000 });
  const reason = new DOMException("Synthetic deadline expired", "TimeoutError");
  controller.abort(reason);
  await assert.rejects(result, (error) => error === reason);
  assert.deepEqual(durations, [95000]);
});

test("server errors retain the message and correlation reference with an extended deadline", async (t) => {
  deadline(t);
  t.mock.method(globalThis, "fetch", async () =>
    Response.json(
      {
        message: "Anonymous GitHub quota exhausted",
        correlationId: "fixture-ref",
      },
      { status: 429 },
    ),
  );
  await assert.rejects(api(token, "/repository/github", { timeoutMs: 95000 }), {
    message: "Anonymous GitHub quota exhausted · Reference fixture-ref",
  });
});

test("HTTP failures without a server message keep their status", async (t) => {
  deadline(t);
  t.mock.method(globalThis, "fetch", async () =>
    Response.json({}, { status: 503 }),
  );
  await assert.rejects(api(token, "/repository"), {
    message: "Request failed (503)",
  });
});

test("network failures are returned unchanged rather than replaced by a timeout message", async (t) => {
  deadline(t);
  const failure = new TypeError("Synthetic connection refused");
  t.mock.method(globalThis, "fetch", async () => {
    throw failure;
  });
  await assert.rejects(api(token, "/repository"), (error) => error === failure);
});
