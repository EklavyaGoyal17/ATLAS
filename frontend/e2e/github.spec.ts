import { test, expect, type Page } from "@playwright/test";

const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
const url = "https://github.com/example/frontend-project";
const commit = "0123456789abcdef0123456789abcdef01234567";

// These fixtures verify browser transport and state only. They do not claim a real GitHub download.
function snapshot(sha = commit) {
  return {
    id:
      sha === commit
        ? "4602f31b-b92d-4491-bb7e-8efed59e9c01"
        : "4602f31b-b92d-4491-bb7e-8efed59e9c02",
    origin: {
      kind: "GITHUB",
      repositoryUrl: url,
      commit: sha,
      directory: "src",
    },
    files: [
      {
        path: "src/App.tsx",
        language: "tsx",
        lines: 1,
        content: "export const App = () => <main>Fixture</main>;",
        warnings: [],
      },
    ],
    symbols: [],
    edges: [],
    calls: [],
    findings: [],
    languages: { tsx: 1 },
    bytes: 50,
  };
}

async function openGitHub(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page
    .getByRole("button", { name: "Open workspace", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Code intelligence", exact: true })
    .click();
  await page.getByRole("radio", { name: "GitHub link", exact: true }).check();
}

async function approve(page: Page) {
  await page
    .getByLabel("Public GitHub repository URL", { exact: true })
    .fill(url);
  await page
    .getByLabel("Repository subdirectory (optional)", { exact: true })
    .fill("src");
  await page
    .getByLabel("I approve downloading this public repository", { exact: true })
    .check();
}

test("GitHub import UI transport fixture binds approval to URL and scope and displays pinned provenance", async ({
  page,
}) => {
  const payloads: unknown[] = [];
  const modelCalls: string[] = [];
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      request.url().includes("/api/assistant/")
    )
      modelCalls.push(request.url());
  });
  await page.route("**/api/repository/github", async (route) => {
    payloads.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, json: snapshot() });
  });
  await openGitHub(page);
  const button = page.getByRole("button", {
    name: "Import GitHub repository",
    exact: true,
  });
  const consent = page.getByLabel(
    "I approve downloading this public repository",
    { exact: true },
  );
  await expect(button).toBeDisabled();
  await approve(page);
  await page
    .getByLabel("Public GitHub repository URL", { exact: true })
    .fill(url + ".git");
  await expect(consent).not.toBeChecked();
  await expect(button).toBeDisabled();
  await consent.check();
  await page
    .getByLabel("Repository subdirectory (optional)", { exact: true })
    .fill("src/components");
  await expect(consent).not.toBeChecked();
  await expect(button).toBeDisabled();
  expect(payloads).toHaveLength(0);
  await page
    .getByLabel("Repository subdirectory (optional)", { exact: true })
    .fill("src");
  await consent.check();
  await button.click();
  const origin = page.locator('[aria-label="Imported repository provenance"]');
  await expect(origin).toContainText(commit);
  await expect(origin).toContainText("Scope: src");
  await expect(origin.getByRole("link")).toHaveAttribute(
    "href",
    `${url}/tree/${commit}`,
  );
  expect(payloads).toEqual([
    { url: url + ".git", directory: "src", approved: true },
  ]);
  await expect(page.locator(".repository-import")).toContainText(
    "frontend, backend, library, or full-stack projects",
  );
  await expect(page.locator(".repository-import")).toContainText(
    "synthetic payments lab is a separate learning example",
  );
  await expect(
    page.getByRole("heading", { name: "Symbols & files", exact: true }),
  ).toBeVisible();
  expect(modelCalls).toHaveLength(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("GitHub import UI transport fixture keeps the old snapshot on failure and discards a late unmounted response", async ({
  page,
}) => {
  let attempt = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let started!: () => void;
  const pending = new Promise<void>((resolve) => {
    started = resolve;
  });
  await page.route("**/api/repository/github", async (route) => {
    attempt++;
    if (attempt === 1) await route.fulfill({ status: 200, json: snapshot() });
    else if (attempt === 2)
      await route.fulfill({
        status: 429,
        json: {
          code: "GITHUB_RATE_LIMIT",
          message: "Public GitHub rate limit reached; retry later.",
        },
      });
    else {
      started();
      await gate;
      await route.fulfill({ status: 200, json: snapshot("a".repeat(40)) });
    }
  });
  await openGitHub(page);
  await approve(page);
  const button = page.getByRole("button", {
    name: "Import GitHub repository",
    exact: true,
  });
  await button.click();
  const origin = page.locator('[aria-label="Imported repository provenance"]');
  await expect(origin).toContainText(commit);
  await button.click();
  await expect(
    page.getByRole("alert").filter({
      hasText: "Public GitHub rate limit reached; retry later.",
    }),
  ).toBeVisible();
  await expect(origin).toContainText(commit);
  await button.click();
  await pending;
  await expect(
    page.getByLabel("Public GitHub repository URL", { exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Delete imported snapshot", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("radio", { name: "Local folder", exact: true }),
  ).toBeDisabled();
  try {
    await page
      .getByRole("button", { name: "Payments lab", exact: true })
      .click();
    const completed = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/repository/github") &&
        response.status() === 200,
    );
    release();
    await completed;
    await page
      .getByRole("button", { name: "Code intelligence", exact: true })
      .click();
    await expect(
      page.locator('[aria-label="Imported repository provenance"]'),
    ).toContainText(commit);
    await expect(
      page.locator('[aria-label="Imported repository provenance"]'),
    ).not.toContainText("a".repeat(40));
  } finally {
    release();
  }
});
