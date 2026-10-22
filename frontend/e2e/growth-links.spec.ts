import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
const publicAtlas = "https://github.com/EklavyaGoyal17/ATLAS/blob/main/";
const examples = [
  ["Java", "backend/src/main/java/dev/atlas/payments/PaymentModels.java"],
  [
    "Spring Boot",
    "backend/src/main/java/dev/atlas/payments/PaymentService.java",
  ],
  ["SQL", "backend/src/main/resources/db/migration/V1__payment_core.sql"],
  ["Concurrency", "backend/src/main/java/dev/atlas/payments/PaymentStore.java"],
  ["Networking", "scripts/load.mjs"],
  ["APIs", "backend/src/main/java/dev/atlas/payments/PaymentService.java"],
  [
    "Distributed systems",
    "backend/src/main/java/dev/atlas/eventing/DeliveryService.java",
  ],
  ["System design", "docs/DECISIONS/001-modular-monolith.md"],
  [
    "Testing",
    "backend/src/test/java/dev/atlas/payments/PaymentIntegrationTest.java",
  ],
  ["Debugging", "backend/src/main/java/dev/atlas/observability/Telemetry.java"],
  [
    "Security",
    "backend/src/main/java/dev/atlas/intelligence/RepositoryIndex.java",
  ],
  ["Cloud infrastructure", "compose.yaml"],
  [
    "Observability",
    "backend/src/main/java/dev/atlas/observability/IncidentService.java",
  ],
  [
    "AI-assisted engineering",
    "backend/src/main/java/dev/atlas/intelligence/AssistantService.java",
  ],
] as const;

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page
    .getByRole("button", { name: "Open workspace", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Engineering growth", exact: true })
    .click();
});

test("learning examples link existing source and walkthrough anchors with an external navigation fixture", async ({
  page,
  context,
}) => {
  const writes: string[] = [];
  page.on("request", (request) => {
    if (
      new URL(request.url()).pathname.startsWith("/api/") &&
      request.method() !== "GET"
    )
      writes.push(new URL(request.url()).pathname);
  });
  const roadmap = page.getByRole("region", {
    name: "Learning roadmap",
    exact: true,
  });
  await expect(roadmap.locator("article")).toHaveCount(14);
  await expect(roadmap).toContainText("follow the main branch");
  await expect(roadmap).toContainText("does not mark it complete");
  for (const [area, path] of examples) {
    const card = roadmap.getByRole("article").filter({
      has: page.getByRole("heading", { name: area, exact: true }),
    });
    const implementation = card.getByRole("link", { name: path, exact: true });
    await expect(implementation).toHaveAttribute("href", publicAtlas + path);
    await expect(implementation).toHaveAttribute("target", "_blank");
    await expect(implementation).toHaveAttribute("rel", "noopener noreferrer");
    expect(
      (await readFile(new URL(`../../${path}`, import.meta.url), "utf8"))
        .length,
    ).toBeGreaterThan(0);
    const walkthrough = card.getByRole("link", { name: /^Open walkthrough: / });
    const href = await walkthrough.getAttribute("href");
    expect(href?.startsWith(publicAtlas)).toBe(true);
    const [document, anchor] = href!.slice(publicAtlas.length).split("#");
    expect([
      "docs/DEMO.md",
      "docs/RECOVERY.md",
      "docs/ARCHITECTURE.md",
    ]).toContain(document);
    expect(anchor).toBeTruthy();
    const markdown = await readFile(
      new URL(`../../${document}`, import.meta.url),
      "utf8",
    );
    const headingAnchors = markdown
      .split(/\r?\n/)
      .filter((line) => /^#{1,6} /.test(line))
      .map((line) =>
        line
          .replace(/^#{1,6} /, "")
          .toLowerCase()
          .replace(/[^a-z0-9_\s-]/g, "")
          .replace(/\s/g, "-"),
      );
    expect(headingAnchors).toContain(anchor);
    await expect(walkthrough).toHaveAttribute("target", "_blank");
    await expect(walkthrough).toHaveAttribute("rel", "noopener noreferrer");
  }
  // External navigation is a clearly labelled transport fixture, not a live GitHub page check.
  await context.route("https://github.com/**", (route) =>
    route.fulfill({
      contentType: "text/html",
      body: "<!doctype html><title>Public ATLAS navigation fixture</title><p>Public documentation</p>",
    }),
  );
  const newTab = context.waitForEvent("page");
  await roadmap
    .getByRole("link", { name: examples[0][1], exact: true })
    .click();
  const opened = await newTab;
  await opened.waitForLoadState();
  expect(opened.url()).toBe(publicAtlas + examples[0][1]);
  expect(await opened.evaluate(() => window.opener === null)).toBe(true);
  await opened.close();
  expect(writes).toEqual([]);
  await expect(page.getByLabel("Record title", { exact: true })).toHaveValue(
    "",
  );
  await expect(
    page.getByRole("button", { name: "Save engineering record", exact: true }),
  ).toBeDisabled();
});

test("technical review follows selected public evidence without importing code or marking progress", async ({
  page,
}) => {
  const writes: string[] = [];
  page.on("request", (request) => {
    if (
      new URL(request.url()).pathname.startsWith("/api/") &&
      request.method() !== "GET"
    )
      writes.push(new URL(request.url()).pathname);
  });
  await page.setViewportSize({ width: 390, height: 844 });
  const review = page.getByRole("region", {
    name: "Technical review mode",
    exact: true,
  });
  await review.getByLabel("Review area", { exact: true }).selectOption("13");
  const source = review.getByRole("link", {
    name: examples[13][1],
    exact: true,
  });
  await expect(source).toHaveAttribute("href", publicAtlas + examples[13][1]);
  await expect(
    review.getByRole("link", {
      name: "Open walkthrough: Grounded technical review",
      exact: true,
    }),
  ).toHaveAttribute(
    "href",
    publicAtlas + "docs/DEMO.md#demo-5--grounded-technical-review",
  );
  await review
    .getByLabel("Your technical answer")
    .fill("Inspect the source, run a test, and explain uncertainty.");
  await review
    .getByRole("button", { name: "Evaluate against rubric", exact: true })
    .click();
  await expect(review.locator(".answer")).toContainText(
    "Keyword presence is not proof of understanding",
  );
  await expect(review.locator(".answer")).toContainText(
    "linked public implementation and its tests",
  );
  await review.getByLabel("Review area", { exact: true }).selectOption("11");
  await expect(
    review.getByRole("link", { name: "compose.yaml", exact: true }),
  ).toHaveAttribute("href", publicAtlas + "compose.yaml");
  await expect(
    review.getByRole("link", {
      name: "Open walkthrough: Isolated application backup and readback",
      exact: true,
    }),
  ).toHaveAttribute(
    "href",
    publicAtlas +
      "docs/RECOVERY.md#application-created-backup-and-restored-api-readback",
  );
  await expect(review.getByLabel("Your technical answer")).toHaveValue("");
  await expect(review.locator(".answer")).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(writes).toEqual([]);
});
