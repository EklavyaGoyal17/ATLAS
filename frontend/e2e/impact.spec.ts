import { test, expect, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";

const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
const storePath = "backend/src/main/java/dev/atlas/payments/PaymentStore.java";

type ImpactReport = {
  snapshotId: string;
  selectedPaths: string[];
  seeds: { id: string; name: string }[];
  affected: {
    type: { id: string; name: string };
    distance: number;
    changedTypeId: string;
    path: { from: string; to: string; path: string; line: number }[];
  }[];
};

async function importApprovedRepository(page: Page) {
  await page
    .getByRole("button", { name: "Code intelligence", exact: true })
    .click();
  await page.getByLabel("I approve reading this repository").check();
  const imported = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/repository/import" &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Import repository", exact: true })
    .click();
  const response = await imported;
  expect(response.ok()).toBeTruthy();
  // The automatic architecture review and manual impact analysis share one bounded parser slot.
  await expect(
    page
      .getByRole("region", { name: "Architecture review", exact: true })
      .locator(".architecture-summary"),
  ).toContainText("resolved types", { timeout: 20000 });
  return (await response.json()) as { id: string };
}

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page.getByRole("button", { name: "Open workspace" }).click();
});

test("change impact exports exact snapshot and source evidence, and selection edits clear results", async ({
  page,
}) => {
  const snapshot = await importApprovedRepository(page);
  const explorer = page.getByRole("region", {
    name: "Change impact explorer",
    exact: true,
  });
  const analyze = explorer.getByRole("button", {
    name: "Analyze selected files",
    exact: true,
  });
  await expect(analyze).toBeDisabled();
  await explorer
    .getByLabel("Find impact file", { exact: true })
    .fill("PaymentStore.java");
  const checkbox = explorer.getByRole("checkbox", {
    name: storePath,
    exact: true,
  });
  await checkbox.check();
  await analyze.click();
  await expect(explorer.getByRole("status")).toContainText(
    "reachable dependant types",
    { timeout: 20000 },
  );
  const service = explorer.locator("details").filter({
    has: page.locator("summary").filter({
      hasText: /^dev\.atlas\.payments\.PaymentService · direct dependant$/,
    }),
  });
  await service.locator("summary").click();
  await service
    .getByRole("button", { name: /^Open dependant declaration/ })
    .click();
  await expect(page.locator(".source-code")).toContainText(
    "class PaymentService",
  );
  await service.locator("ol button").first().click();
  await expect(page.locator(".source-code .highlight-line")).toContainText(
    "PaymentStore store;",
  );

  const downloaded = page.waitForEvent("download");
  await explorer
    .getByRole("button", { name: "Export change impact", exact: true })
    .click();
  const download = await downloaded;
  expect(download.suggestedFilename()).toBe("atlas-change-impact.json");
  const report = JSON.parse(
    await readFile((await download.path())!, "utf8"),
  ) as ImpactReport;
  expect(report.snapshotId).toBe(snapshot.id);
  expect(report.selectedPaths).toEqual([storePath]);
  const store = report.seeds.find(
    (type) => type.name === "dev.atlas.payments.PaymentStore",
  )!;
  const affected = report.affected.find(
    (item) => item.type.name === "dev.atlas.payments.PaymentService",
  )!;
  expect(store).toBeTruthy();
  expect(affected.distance).toBe(1);
  expect(affected.changedTypeId).toBe(store.id);
  expect(affected.path).toHaveLength(1);
  expect(affected.path[0].from).toBe(affected.type.id);
  expect(affected.path[0].to).toBe(store.id);
  expect(affected.path[0].path).toBe(
    "backend/src/main/java/dev/atlas/payments/PaymentService.java",
  );
  expect(affected.path[0].line).toBeGreaterThan(0);
  await explorer
    .getByText("Change impact scope and limitations", { exact: true })
    .click();
  await expect(explorer).toContainText(
    "does not establish runtime propagation",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);

  await checkbox.uncheck();
  await expect(
    explorer.getByRole("button", { name: "Export change impact", exact: true }),
  ).toHaveCount(0);
  await expect(
    explorer.getByRole("heading", {
      name: "Dependants to review",
      exact: true,
    }),
  ).toHaveCount(0);
  await expect(analyze).toBeDisabled();
});

test("late real impact response cannot restore a result for an edited selection", async ({
  page,
}) => {
  await importApprovedRepository(page);
  const explorer = page.getByRole("region", {
    name: "Change impact explorer",
    exact: true,
  });
  await explorer
    .getByLabel("Find impact file", { exact: true })
    .fill("PaymentStore.java");
  const checkbox = explorer.getByRole("checkbox", {
    name: storePath,
    exact: true,
  });
  await checkbox.check();
  let release!: () => void;
  let ready!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const fetched = new Promise<void>((resolve) => {
    ready = resolve;
  });
  await page.route("**/api/repository/impact", async (route) => {
    const response = await route.fetch();
    ready();
    await held;
    await route.fulfill({ response });
  });
  const arrived = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/repository/impact",
  );
  await explorer
    .getByRole("button", { name: "Analyze selected files", exact: true })
    .click();
  await fetched;
  await checkbox.uncheck();
  release();
  await (await arrived).finished();
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
  await expect(
    explorer.getByRole("button", { name: "Export change impact", exact: true }),
  ).toHaveCount(0);
  await expect(
    explorer.getByRole("heading", {
      name: "Dependants to review",
      exact: true,
    }),
  ).toHaveCount(0);
  await page.unroute("**/api/repository/impact");
  await checkbox.check();
  await explorer
    .getByRole("button", { name: "Analyze selected files", exact: true })
    .click();
  await expect(explorer.getByRole("status")).toContainText(
    "reachable dependant types",
    { timeout: 20000 },
  );
});

test("indexed text files show an explicit unsupported graph result", async ({
  page,
}) => {
  await importApprovedRepository(page);
  const explorer = page.getByRole("region", {
    name: "Change impact explorer",
    exact: true,
  });
  await explorer
    .getByLabel("Find impact file", { exact: true })
    .fill("README.md");
  await explorer
    .getByRole("checkbox", { name: "README.md", exact: true })
    .check();
  await explorer
    .getByRole("button", { name: "Analyze selected files", exact: true })
    .click();
  await expect(explorer.getByRole("status")).toHaveText(
    "0 changed declarations · 0 reachable dependant types · 0 displayed",
    { timeout: 20000 },
  );
  await expect(explorer).toContainText(
    "Selected files outside the resolved graph (1)",
  );
  await expect(explorer).toContainText(
    "does not establish that the change has no impact",
  );
});
