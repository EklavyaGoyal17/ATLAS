import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";

const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
const headers = { Authorization: `Bearer ${token}` };
const base = "http://127.0.0.1:8080/api";

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page
    .getByRole("button", { name: "Open workspace", exact: true })
    .click();
});

test("operation metrics distinguish real authorization capture and refund without exposing identifiers", async ({
  page,
}) => {
  const create = await page.request.post(`${base}/payments`, {
    headers: { ...headers, "Idempotency-Key": randomUUID() },
    data: { merchantId: "merchant_demo", amountMinor: 12500, currency: "USD" },
  });
  expect(create.ok()).toBeTruthy();
  const id = (await create.json()).payment.id;
  const capture = await page.request.post(`${base}/payments/${id}/capture`, {
    headers: { ...headers, "Idempotency-Key": randomUUID() },
  });
  expect(capture.ok()).toBeTruthy();
  const refund = await page.request.post(`${base}/payments/${id}/refund`, {
    headers: { ...headers, "Idempotency-Key": randomUUID() },
    data: { amountMinor: 2500 },
  });
  expect(refund.ok()).toBeTruthy();
  const response = await page.request.get(`${base}/observability/metrics`, {
    headers,
  });
  expect(response.ok()).toBeTruthy();
  const metrics = await response.json();
  expect(JSON.stringify(metrics)).not.toContain(id);
  expect(JSON.stringify(metrics)).not.toContain(token);
  for (const operation of [
    "HTTP POST /api/payments",
    "HTTP POST /api/payments/{id}/capture",
    "HTTP POST /api/payments/{id}/refund",
  ]) {
    const row = metrics.endpoints.find(
      (item: { operation: string }) => item.operation === operation,
    );
    expect(row, `Missing measured operation ${operation}`).toBeDefined();
    expect(row.requests).toBeGreaterThan(0);
    expect(row.latencySamples).toBeGreaterThan(0);
    expect(row.latencySamples).toBeLessThanOrEqual(
      metrics.endpointLimits.samplesPerOperation,
    );
  }
  await page
    .getByRole("button", { name: "Observability", exact: true })
    .click();
  const table = page.getByRole("region", {
    name: "HTTP operation metrics",
    exact: true,
  });
  await expect(table).toContainText("Different sample counts and windows");
  await expect(
    table.getByRole("rowheader", {
      name: "HTTP POST /api/payments/{id}/capture",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    table.getByRole("rowheader", {
      name: "HTTP POST /api/payments/{id}/refund",
      exact: true,
    }),
  ).toBeVisible();
});

test("saved incident copilot summary cites actual retained evidence and reopens after reload", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Observability", exact: true })
    .click();
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/observability/incidents" &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Run investigation", exact: true })
    .click();
  const createdResponse = await created;
  expect(createdResponse.ok()).toBeTruthy();
  const selected = (await createdResponse.json()).id;
  const brief = page.getByRole("region", {
    name: "Deterministic incident summary",
    exact: true,
  });
  await expect(brief).toContainText(
    "No AI model or private journal context is used",
  );
  await expect(brief).toContainText("3 samples · 3 usable durations");
  await expect(brief).toContainText("db.query");
  await expect(brief).toContainText("does not establish");
  expect(selected).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByLabel("Saved investigation")).toHaveValue(selected);
  const actual = await page.request.get(
    `${base}/assistant/incidents/${selected}/summary`,
    { headers },
  );
  expect(actual.ok()).toBeTruthy();
  const summary = await actual.json();
  const citation = summary.findings
    .flatMap(
      (finding: { citations: { traceId: string; spanId: string | null }[] }) =>
        finding.citations,
    )
    .find((item: { spanId: string | null }) => item.spanId);
  expect(citation).toBeTruthy();
  await brief
    .getByRole("button")
    .filter({ hasText: citation.spanId })
    .first()
    .click();
  await expect(page.getByLabel("Trace ID filter")).toHaveValue(
    citation.traceId,
  );
  await page.reload();
  await page.getByLabel("Workspace token").fill(token);
  await page
    .getByRole("button", { name: "Open workspace", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Observability", exact: true })
    .click();
  await page.getByLabel("Saved investigation").selectOption(selected);
  await expect(brief).toContainText("db.query");
  await expect(
    brief.getByRole("button").filter({ hasText: citation.spanId }).first(),
  ).toBeVisible();
});

test("late real incident summary cannot replace a newly selected investigation", async ({
  page,
}) => {
  const firstResponse = await page.request.post(
    `${base}/observability/incidents`,
    { headers, data: { scenario: "SLOW_QUERY" } },
  );
  const secondResponse = await page.request.post(
    `${base}/observability/incidents`,
    { headers, data: { scenario: "DOWNSTREAM_TIMEOUT" } },
  );
  expect(firstResponse.ok()).toBeTruthy();
  expect(secondResponse.ok()).toBeTruthy();
  const first = await firstResponse.json(),
    second = await secondResponse.json();
  let release!: () => void, markStarted!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const started = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  await page.route(
    `**/assistant/incidents/${first.id}/summary`,
    async (route) => {
      const response = await route.fetch();
      markStarted();
      await gate;
      await route.fulfill({ response });
    },
  );
  try {
    await page
      .getByRole("button", { name: "Observability", exact: true })
      .click();
    await page.getByLabel("Saved investigation").selectOption(first.id);
    await started;
    await page.getByLabel("Saved investigation").selectOption(second.id);
    const brief = page.getByRole("region", {
      name: "Deterministic incident summary",
      exact: true,
    });
    await expect(brief).toContainText("DOWNSTREAM_TIMEOUT");
    const delivered = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `/api/assistant/incidents/${first.id}/summary`,
    );
    release();
    await delivered;
    await expect(brief).toContainText("DOWNSTREAM_TIMEOUT");
    await expect(brief).not.toContainText("SLOW_QUERY");
  } finally {
    release();
  }
});
