import { test, expect, type Page } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";

const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
type Snapshot = {
  id: string;
  files: { path: string; language: string; lines: number; content: string }[];
};
type Metadata = {
  snapshotId: string;
  languages: {
    language: string;
    files: number;
    lines: number;
    contentBytes: number;
    paths: string[];
  }[];
  dependencies: { name: string; path: string; line: number }[];
};

// Generated public fixtures stay under ignored .runtime. No imported code is executed.
async function fixture(files: Record<string, string>) {
  const relative = `.runtime/inventory-e2e-${randomUUID()}`;
  const folder = new URL(`../../${relative}/`, import.meta.url);
  await mkdir(new URL(".git/", folder), { recursive: true });
  for (const [name, content] of Object.entries(files))
    await writeFile(new URL(name, folder), content);
  return relative;
}
async function importFixture(page: Page, path: string) {
  await page
    .getByLabel("Repository path relative to configured root")
    .fill(path);
  await page.getByLabel("I approve reading this repository").check();
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/repository/import" &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Import repository", exact: true })
    .click();
  const result = await response;
  expect(result.ok()).toBeTruthy();
  return (await result.json()) as Snapshot;
}
test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page
    .getByRole("button", { name: "Open workspace", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Code intelligence", exact: true })
    .click();
});

test("real mixed-language inventory matches indexed text and opens exact dependency evidence on a phone", async ({
  page,
}) => {
  const path = await fixture({
    "main.py": "# café: searchable source\nprint('educational fixture')\n",
    "service.go": "package main\nfunc main() {}\n",
    "requirements.txt":
      "# Declared only, never installed\rrequests>=2.0\r-r other.txt\r",
    "go.mod":
      "module example.invalid/inventory\n\nrequire example.invalid/library v1.2.3\n",
  });
  const importedResponse = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/repository/import" &&
      r.request().method() === "POST",
  );
  const metadataResponse = page.waitForResponse(async (r) => {
    if (new URL(r.url()).pathname !== "/api/repository/metadata" || !r.ok())
      return false;
    const imported = (await (await importedResponse).json()) as Snapshot;
    return new URL(r.url()).searchParams.get("snapshotId") === imported.id;
  });
  const snapshot = await importFixture(page, path);
  const response = await metadataResponse;
  const report = (await response.json()) as Metadata;
  expect(new URL(response.url()).searchParams.get("snapshotId")).toBe(
    snapshot.id,
  );
  expect(report.snapshotId).toBe(snapshot.id);
  expect(snapshot.files).toHaveLength(4);
  expect(report.languages.reduce((n, entry) => n + entry.files, 0)).toBe(4);
  for (const entry of report.languages) {
    const files = snapshot.files.filter(
      (file) => file.language === entry.language,
    );
    expect(entry.paths).toEqual(files.map((file) => file.path).sort());
    expect(entry.lines).toBe(files.reduce((n, file) => n + file.lines, 0));
    expect(entry.contentBytes).toBe(
      files.reduce((n, file) => n + Buffer.byteLength(file.content, "utf8"), 0),
    );
  }
  const inventory = page.getByRole("region", {
    name: "Repository inventory",
    exact: true,
  });
  await expect(inventory.getByRole("status")).toHaveText(
    "4 matching indexed files",
  );
  await expect(inventory).toContainText(
    "structured AST analysis currently covers Java only",
  );
  await inventory.getByLabel("Filter indexed language").selectOption("py");
  await expect(inventory.getByRole("button")).toHaveCount(1);
  await inventory
    .getByRole("button", { name: "Open main.py", exact: true })
    .click();
  await expect(page.locator(".source-code")).toContainText("café");
  await inventory.getByLabel("Search indexed paths").fill("missing");
  await expect(inventory.getByRole("status")).toHaveText(
    "0 matching indexed files",
  );
  await page
    .getByText("Modules, framework signals and manifest dependencies", {
      exact: true,
    })
    .click();
  await expect(
    page.getByRole("button", { name: "Open requirements.txt:2", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Open requirements.txt:2", exact: true })
    .click();
  await expect(page.locator(".source-code .highlight-line")).toContainText(
    "requests>=2.0",
  );
  await page
    .getByRole("button", { name: "Open go.mod:3", exact: true })
    .click();
  await expect(page.locator(".source-code .highlight-line")).toContainText(
    "require example.invalid/library v1.2.3",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("late real metadata cannot replace a new snapshot and inventory filters reset", async ({
  page,
}) => {
  const first = await fixture({
    "first.py": "# first inventory\n",
    "requirements.txt": "requests>=2\n",
  });
  const second = await fixture({ "second.md": "# Replacement inventory\n" });
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let ready!: () => void;
  const held = new Promise<void>((resolve) => {
    ready = resolve;
  });
  let hold = false;
  let heldSnapshot = "";
  await page.route("**/api/repository/metadata?*", async (route) => {
    const response = await route.fetch();
    const report = (await response.json()) as Metadata;
    if (hold && !heldSnapshot) {
      heldSnapshot = report.snapshotId;
      ready();
      await gate;
    }
    await route.fulfill({ response, json: report });
  });
  try {
    const initial = await importFixture(page, first);
    const inventory = page.getByRole("region", {
      name: "Repository inventory",
      exact: true,
    });
    await expect(inventory.getByRole("status")).toHaveText(
      "2 matching indexed files",
    );
    await inventory.getByLabel("Filter indexed language").selectOption("py");
    await inventory.getByLabel("Search indexed paths").fill("first");
    hold = true;
    // A new real import starts a fresh metadata request; its actual payload is held.
    const pending = await importFixture(page, first);
    expect(pending.id).not.toBe(initial.id);
    await held;
    const replacement = await importFixture(page, second);
    expect(replacement.id).not.toBe(heldSnapshot);
    await expect(inventory.getByRole("status")).toHaveText(
      "1 matching indexed files",
    );
    await expect(inventory.getByLabel("Filter indexed language")).toHaveValue(
      "",
    );
    await expect(inventory.getByLabel("Search indexed paths")).toHaveValue("");
    const completed = page.waitForResponse(
      (r) =>
        new URL(r.url()).searchParams.get("snapshotId") === heldSnapshot &&
        r.ok(),
    );
    release();
    await completed;
    await expect(
      inventory.getByRole("button", { name: "Open second.md", exact: true }),
    ).toBeVisible();
    await expect(inventory).not.toContainText("first.py");
    await page
      .getByRole("button", { name: "Delete imported snapshot", exact: true })
      .click();
    await expect(inventory).toHaveCount(0);
  } finally {
    release();
  }
});

test("metadata identity mismatch and conflict are withheld and can be refreshed", async ({
  page,
}) => {
  const path = await fixture({ "readme.md": "# Current source\n" });
  let mismatch = true;
  let conflict = false;
  await page.route("**/api/repository/metadata?*", async (route) => {
    if (conflict) {
      // Explicit stale-response transport fixture, alongside the identity fixture below.
      await route.fulfill({
        status: 409,
        json: {
          code: "STALE_SNAPSHOT",
          message:
            "The approved snapshot changed. Refresh it before reviewing repository metadata.",
        },
      });
      return;
    }
    const response = await route.fetch();
    const report = await response.json();
    // This is an explicit response-identity fixture; all report content is real.
    if (mismatch) report.snapshotId = randomUUID();
    await route.fulfill({ response, json: report });
  });
  await importFixture(page, path);
  const refresh = page.getByRole("button", {
    name: "Refresh repository inventory",
    exact: true,
  });
  await expect(refresh).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Repository inventory", exact: true }),
  ).toHaveCount(0);
  mismatch = false;
  await refresh.click();
  await expect(
    page
      .getByRole("region", { name: "Repository inventory", exact: true })
      .getByRole("button", { name: "Open readme.md", exact: true }),
  ).toBeVisible();
  conflict = true;
  await importFixture(page, path);
  const retry = page.getByRole("button", {
    name: "Retry repository inventory",
    exact: true,
  });
  await expect(retry).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Repository inventory", exact: true }),
  ).toHaveCount(0);
  conflict = false;
  await retry.click();
  await expect(
    page
      .getByRole("region", { name: "Repository inventory", exact: true })
      .getByRole("button", { name: "Open readme.md", exact: true }),
  ).toBeVisible();
});
