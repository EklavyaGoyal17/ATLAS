import { test, expect, type Page } from "@playwright/test";
import { reviewCriteria } from "../src/reviewEvidence";

const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
async function openGrowth(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page
    .getByRole("button", { name: "Open workspace", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Engineering growth", exact: true })
    .click();
}

test("ADR requires six complete fields and a calendar date before a real save", async ({
  page,
}) => {
  await openGrowth(page);
  await page.getByLabel("Record type", { exact: true }).selectOption("ADR");
  await page
    .getByLabel("Record title", { exact: true })
    .fill(`Synthetic ADR ${crypto.randomUUID()}`);
  await page
    .getByLabel(
      "This note contains only generalized, non-confidential information",
    )
    .check();
  const save = page.getByRole("button", {
    name: "Save engineering record",
    exact: true,
  });
  await expect(save).toBeDisabled();
  for (const field of [
    "Context",
    "Decision",
    "Alternatives",
    "Tradeoffs",
    "Consequences",
  ]) {
    await page
      .getByLabel(field, { exact: true })
      .fill(`Synthetic public ${field.toLowerCase()} evidence.`);
    await expect(save).toBeDisabled();
  }
  const reviewDate = page.getByLabel("Review date", { exact: true });
  await expect(reviewDate).toHaveAttribute("type", "date");
  await expect(reviewDate).toHaveAttribute("min", "0001-01-01");
  await expect(reviewDate).toHaveAttribute("max", "9999-12-31");
  await reviewDate.fill("2028-02-29");
  await expect(save).toBeEnabled();
  const written = page.waitForResponse(
    (response) =>
      response.request().method() === "PUT" &&
      response.url().includes("/api/workspace/documents/"),
  );
  await save.click();
  const response = await written;
  expect(response.ok()).toBe(true);
  const record = await response.json();
  try {
    expect(record.kind).toBe("ADR");
    expect(Object.keys(record.body).sort()).toEqual(
      [
        "Context",
        "Decision",
        "Alternatives",
        "Tradeoffs",
        "Consequences",
        "Review date",
      ].sort(),
    );
    expect(record.body["Review date"]).toBe("2028-02-29");
    await expect(page.getByRole("status")).toContainText(
      "Engineering note saved",
    );
  } finally {
    const deleted = await page.request.delete(
      `/api/workspace/documents/${record.id}`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(deleted.ok()).toBe(true);
  }
});

test("review distinguishes keyword coverage from self-reported reference evidence and persists its limits", async ({
  page,
}) => {
  await openGrowth(page);
  const review = page.getByRole("region", {
    name: "Technical review mode",
    exact: true,
  });
  const criterion = reviewCriteria.find((item) => item.area === "APIs")!;
  await review
    .getByLabel("Your technical answer")
    .fill(
      "An advisory fingerprint transaction is not by itself proof of correctness.",
    );
  const evaluate = review.getByRole("button", {
    name: "Evaluate against rubric",
    exact: true,
  });
  await evaluate.click();
  await expect(review.locator(".answer")).toContainText("Mentioned: advisory");
  await expect(review.locator(".answer")).toContainText(
    "Evidence incomplete or references unmatched",
  );
  await expect(review.locator(".answer")).toContainText(
    "Human verification: Not independently verified",
  );

  await review
    .getByLabel("Source reference inspected", { exact: true })
    .fill(`https://github.com/${criterion.source}`);
  await review
    .getByLabel("Test reference inspected", { exact: true })
    .fill(criterion.test);
  const observation = `My local conflict/replay test returned the original reply and rejected changed intent; I have not verified production behavior. Synthetic case ${crypto.randomUUID()}.`;
  await review
    .getByLabel("Observed result and remaining uncertainty")
    .fill(observation);
  await review
    .getByLabel(
      "I inspected these public references; my observation is self-reported",
    )
    .check();
  await evaluate.click();
  await expect(review.locator(".answer")).toContainText(
    "Evidence incomplete or references unmatched",
  );

  await review
    .getByLabel("Source reference inspected", { exact: true })
    .fill(criterion.source);
  await expect(review.locator(".answer")).toHaveCount(0);
  await evaluate.click();
  await expect(review.locator(".answer")).toContainText(
    "Reference-matched self-reported evidence supplied",
  );
  await expect(review.locator(".answer")).toContainText(
    "not whether a test ran",
  );
  const written = page.waitForResponse(
    (response) =>
      response.request().method() === "PUT" &&
      response.url().includes("/api/workspace/documents/"),
  );
  await review
    .getByRole("button", { name: "Save review answer", exact: true })
    .click();
  const response = await written;
  expect(response.ok()).toBe(true);
  const record = await response.json();
  try {
    expect(record.kind).toBe("REVIEW");
    expect(record.body.evidence).toEqual({
      sourceReference: criterion.source,
      testReference: criterion.test,
      observation,
      inspected: true,
    });
    expect(record.body.criterion.failureCase).toBe(criterion.failureCase);
    expect(record.body.assessment.evidenceSupplied).toBe(true);
    expect(record.body.assessment.humanVerification).toBe(
      "Not independently verified",
    );
    expect(record.body.assessment.caveats).toContain(
      "Reference matching checks the reviewed public allowlist, not whether a test ran or an observation is true.",
    );
    await page.reload();
    await page.getByLabel("Workspace token").fill(token);
    await page
      .getByRole("button", { name: "Open workspace", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Engineering growth", exact: true })
      .click();
    // IDs are API identity, not rendered labels: select the exact saved title and verify its retained observation.
    const entry = page
      .locator("details.record")
      .filter({
        has: page.locator("summary").filter({ hasText: "Review: APIs" }),
      })
      .filter({ hasText: observation });
    await expect(entry).toHaveCount(1);
    await entry.locator("summary").click();
    await expect(entry.locator("pre")).toContainText(
      "Not independently verified",
    );
    await expect(entry.locator("pre")).toContainText(observation);
  } finally {
    const deleted = await page.request.delete(
      `/api/workspace/documents/${record.id}`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(deleted.ok()).toBe(true);
  }
});

test("legacy ADR display fixture remains readable and editing requires completion without dropping extras", async ({
  page,
}) => {
  const id = crypto.randomUUID();
  let writes = 0;
  let submitted: Record<string, unknown> | undefined;
  // Explicit legacy transport fixture: the new API rejects creating incomplete
  // ADRs, so this checks legacy rendering/edit admission and the submitted
  // client payload only; the fixture does not verify server persistence.
  await page.route("**/api/workspace/documents?**", async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    const url = new URL(route.request().url());
    await route.fulfill({
      json: url.searchParams.has("kind")
        ? []
        : [
            {
              id,
              kind: "ADR",
              title: "Legacy synthetic ADR fixture",
              version: 1,
              updatedAt: "2026-10-21T00:00:00Z",
              body: {
                Context: "Public context",
                Decision: "Prior choice",
                legacyExtra: { retained: true },
              },
            },
          ],
    });
  });
  await page.route(`**/api/workspace/documents/${id}`, async (route) => {
    if (route.request().method() !== "PUT") return route.continue();
    submitted = route.request().postDataJSON();
    await route.fulfill({
      json: {
        id,
        kind: "ADR",
        title: "Legacy synthetic ADR fixture",
        version: 2,
        body: submitted!.body,
        updatedAt: "2026-10-22T00:00:00Z",
      },
    });
  });
  page.on("request", (request) => {
    if (
      request.method() === "PUT" &&
      request.url().includes("/api/workspace/documents/")
    )
      writes++;
  });
  await openGrowth(page);
  const record = page
    .locator("details.record")
    .filter({ hasText: "Legacy synthetic ADR fixture" });
  await record.locator("summary").click();
  await expect(record.locator("pre")).toContainText('"legacyExtra"');
  await record
    .getByRole("button", { name: "Edit record", exact: true })
    .click();
  await expect(
    page.getByText("All six ADR fields are required.", { exact: false }),
  ).toBeVisible();
  await expect(page.getByLabel("Context", { exact: true })).toHaveValue(
    "Public context",
  );
  await expect(page.getByLabel("Alternatives", { exact: true })).toHaveValue(
    "",
  );
  await page
    .getByLabel(
      "This note contains only generalized, non-confidential information",
    )
    .check();
  await expect(
    page.getByRole("button", { name: "Save engineering record", exact: true }),
  ).toBeDisabled();
  expect(writes).toBe(0);
  await expect(record.locator("pre")).toContainText('"retained": true');
  for (const field of ["Alternatives", "Tradeoffs", "Consequences"]) {
    await page
      .getByLabel(field, { exact: true })
      .fill(`Public completed ${field}.`);
  }
  await page.getByLabel("Review date", { exact: true }).fill("2026-11-01");
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === "PUT" &&
      response.url().endsWith(`/workspace/documents/${id}`),
  );
  await page
    .getByRole("button", { name: "Save engineering record", exact: true })
    .click();
  expect((await saved).ok()).toBe(true);
  expect(writes).toBe(1);
  expect(submitted!.expectedVersion).toBe(1);
  expect((submitted!.body as Record<string, unknown>).legacyExtra).toEqual({
    retained: true,
  });
});
