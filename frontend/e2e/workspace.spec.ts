import { test, expect } from "@playwright/test";
const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page.getByRole("button", { name: "Open workspace" }).click();
});
test("approved repository, parsed source navigation, local evidence and context preview", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Code intelligence", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Import repository", exact: true }),
  ).toBeDisabled();
  await page.getByLabel("I approve reading this repository").check();
  await page
    .getByRole("button", { name: "Import repository", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Symbols & files" }),
  ).toBeVisible({ timeout: 20000 });
  await page.getByLabel("Search symbols").fill("authorize");
  await page
    .locator(".symbol-list button")
    .filter({ hasText: "endpoint" })
    .first()
    .click();
  await expect(page.locator(".source-code")).toContainText("PostMapping");
  await page
    .getByLabel("Engineering question")
    .fill("Where is payment authorization transactional?");
  await page
    .getByRole("button", { name: "Find local evidence", exact: true })
    .click();
  await expect(page.locator(".answer")).toContainText("LOCAL_EVIDENCE");
  await expect(page.locator(".answer")).toContainText(
    "Suggested investigation",
  );
  await expect(page.locator(".answer details").first()).toBeVisible();
  await page
    .getByRole("button", { name: "Preview provider context", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Exact redacted excerpts for approval" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Ask configured provider", exact: true }),
  ).toBeDisabled();
  await page
    .getByLabel("Repository path relative to configured root")
    .fill("../outside");
  await page.getByLabel("I approve reading this repository").check();
  await page
    .getByRole("button", { name: "Import repository", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("relative path");
});

test("stale previews and answers cannot restore consent after a question changes", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Code intelligence", exact: true })
    .click();
  await page.getByLabel("I approve reading this repository").check();
  await Promise.all([
    page.waitForResponse(
      (r) => new URL(r.url()).pathname === "/api/repository/import",
    ),
    page
      .getByRole("button", { name: "Import repository", exact: true })
      .click(),
  ]);
  await expect(
    page.getByRole("heading", { name: "Symbols & files" }),
  ).toBeVisible({ timeout: 20000 });

  for (const endpoint of ["/api/assistant/preview", "/api/assistant"]) {
    let release!: () => void;
    let ready!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const fetched = new Promise<void>((resolve) => {
      ready = resolve;
    });
    await page.route(`**${endpoint}`, async (route) => {
      const response = await route.fetch(); // Keep the real API result; delay only delivery.
      ready();
      await held;
      await route.fulfill({ response });
    });
    const arrived = page.waitForResponse(
      (r) => new URL(r.url()).pathname === endpoint,
    );
    await page
      .getByRole("button", {
        name: endpoint.endsWith("preview")
          ? "Preview provider context"
          : "Find local evidence",
        exact: true,
      })
      .click();
    await fetched;
    await page
      .getByLabel("Engineering question")
      .fill(`New authorize transaction question ${endpoint}`);
    release();
    await (await arrived).finished();
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
    await expect(page.locator(".context-preview")).toHaveCount(0);
    await expect(page.locator(".answer")).toHaveCount(0);
    await page.unroute(`**${endpoint}`);
  }
  await page
    .getByRole("button", { name: "Preview provider context", exact: true })
    .click();
  await expect(page.locator(".context-preview")).toBeVisible();
  await page
    .getByRole("checkbox", { name: "I approve sending this question" })
    .check();
  await page
    .getByRole("button", { name: "Find local evidence", exact: true })
    .click();
  await expect(page.locator(".answer")).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => new URL(r.url()).pathname === "/api/repository/import",
    ),
    page
      .getByRole("button", { name: "Import repository", exact: true })
      .click(),
  ]);
  await expect(page.locator(".answer")).toHaveCount(0);
  await expect(page.locator(".context-preview")).toHaveCount(0);
});

test("real isolated connection pool times out and recovers with trace evidence", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Observability", exact: true })
    .click();
  await page
    .getByLabel("Incident scenario")
    .selectOption("CONNECTION_EXHAUSTION");
  await page
    .getByRole("button", { name: "Run investigation", exact: true })
    .click();
  await expect(page.locator(".trace-sample")).toHaveCount(6);
  await expect(
    page.locator(".trace-sample").filter({ hasText: "POOL_TIMEOUT_RECOVERED" }),
  ).toHaveCount(3);
  await page
    .locator(".trace-sample")
    .filter({ hasText: "POOL_TIMEOUT_RECOVERED" })
    .first()
    .click();
  await expect(page.locator(".trace-list")).toContainText("db.pool.acquire");
  await expect(page.locator(".trace-list")).toContainText("db.pool.recovered");
});
test("measured incident, trace selection and persistent postmortem", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Observability", exact: true })
    .click();
  await page.getByLabel("Incident scenario").selectOption("SLOW_QUERY");
  await page
    .getByRole("button", { name: "Run investigation", exact: true })
    .click();
  await expect(page.locator(".trace-sample")).toHaveCount(6);
  await expect(page.locator(".trace-list")).toContainText("db.query");
  await page
    .getByLabel("Your root-cause hypothesis")
    .fill("The injected query waits inside PostgreSQL.");
  await page
    .getByLabel("Validation and corrective action")
    .fill("Compare affected db.query spans with the three baseline samples.");
  await page
    .getByRole("button", { name: "Save postmortem", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Postmortem saved");
  await page
    .getByRole("button", { name: "Engineering growth", exact: true })
    .click();
  await expect(
    page
      .locator(".record")
      .filter({ hasText: "Postmortem: SLOW_QUERY" })
      .first(),
  ).toBeVisible();
});
test("editable diagram, optimistic versions, SVG export and reload from database", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Architecture studio", exact: true })
    .click();
  const title = `Browser architecture ${Date.now()}`;
  await page.getByLabel("Architecture title", { exact: true }).fill(title);
  await page
    .getByLabel("Component name", { exact: true })
    .fill("Payment gateway");
  await page
    .getByRole("textbox", { name: "Responsibility", exact: true })
    .fill("Validate caller intent and forward a bounded request.");
  await page
    .getByRole("button", { name: "Component Payment gateway", exact: true })
    .focus();
  await page.keyboard.press("ArrowRight");
  await page
    .getByRole("button", { name: "Save architecture version", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText("Saved version 1");
  await page
    .getByRole("textbox", { name: "Design assumptions", exact: true })
    .fill("Synthetic workload; one region and no measured capacity claim.");
  await page
    .getByRole("button", { name: "Save architecture version", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText("Saved version 2");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export SVG", exact: true }).click();
  expect((await download).suggestedFilename()).toBe("atlas-architecture.svg");
  await page
    .getByRole("button", { name: "Load saved versions", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Preview v1", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Engineering growth", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Architecture studio", exact: true })
    .click();
  await page
    .getByLabel("Saved architecture")
    .selectOption({ label: `${title} · v2` });
  await expect(
    page.getByLabel("Architecture title", { exact: true }),
  ).toHaveValue(title);
});
test("safe engineering journal and evidence rubric on a phone", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Engineering growth", exact: true })
    .click();
  const title = `Journal ${Date.now()}`;
  await page.getByLabel("Record title", { exact: true }).fill(title);
  await page
    .getByLabel("Problem", { exact: true })
    .fill("Duplicate synthetic requests");
  await page
    .getByLabel("Lesson", { exact: true })
    .fill("Retry the original intent with its original key.");
  await expect(
    page.getByRole("button", { name: "Save engineering record", exact: true }),
  ).toBeDisabled();
  await page
    .getByLabel(
      "This note contains only generalized, non-confidential information",
    )
    .check();
  await page
    .getByRole("button", { name: "Save engineering record", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Engineering note saved",
  );
  await page
    .getByRole("combobox", { name: "Review area", exact: true })
    .selectOption("5");
  await page
    .getByLabel("Your technical answer")
    .fill(
      "Use an advisory lock per intent, compare the fingerprint, and store the immutable response in the same transaction.",
    );
  await page
    .getByRole("button", { name: "Evaluate against rubric", exact: true })
    .click();
  await expect(page.locator(".answer")).toContainText("Mentioned: advisory");
  await expect(page.locator(".answer")).toContainText(
    "Keyword presence is not proof",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page
    .getByRole("button", { name: "Architecture studio", exact: true })
    .click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
test("deterministic saga model and signed duplicate webhook", async ({
  page,
}) => {
  await page.getByRole("button", { name: "Systems lab", exact: true }).click();
  await page
    .getByRole("combobox", { name: "Model scenario", exact: true })
    .selectOption("SAGA_COMPENSATION");
  await page
    .getByRole("button", { name: "Run logical experiment", exact: true })
    .click();
  await expect(page.getByText("Final stated invariant:")).toContainText(
    "holds",
  );
  await expect(
    page.getByText("DUPLICATE_COMPENSATION", { exact: false }),
  ).toBeVisible();
  const prepared = await page.request.post(
    "http://127.0.0.1:8080/api/experiments",
    {
      headers: {
        Authorization: `Bearer ${token}`,
        "Idempotency-Key": crypto.randomUUID(),
      },
      data: { scenario: "NONE" },
    },
  );
  expect(prepared.ok()).toBeTruthy();
  const { eventId } = await prepared.json();
  const sent = await page.request.post(
    `http://127.0.0.1:8080/api/webhooks/demo/${eventId}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(sent.ok()).toBeTruthy();
  const evidence = await sent.json();
  expect(evidence.effects).toBe(1);
  expect(
    evidence.deliveries.filter((d: { duplicate: boolean }) => d.duplicate),
  ).toHaveLength(2);
});
