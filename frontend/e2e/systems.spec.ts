import { test, expect } from "@playwright/test";
const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
test.beforeEach(async ({ page }) => {
  // Return the shared synthetic worker to a deliberate paused state; no records are deleted.
  await page.request.put("http://127.0.0.1:8080/api/worker", {
    headers: { Authorization: `Bearer ${token}` },
    data: { running: false },
  });
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Systems lab", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Failure, made inspectable." }),
  ).toBeVisible();
});
test("committed effect survives lost acknowledgement and duplicate delivery", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Prepare crash after commit" })
    .click();
  const evidence = page.getByRole("region", { name: "Delivery evidence" });
  await expect(evidence.getByTestId("event-effects")).toHaveText("0");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("processing");
  await expect(evidence.getByTestId("event-effects")).toHaveText("1");
  await expect(evidence.getByRole("status")).toContainText(
    "crash after commit",
  );
  await page.getByLabel("Advance logical clock").selectOption("2");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("delivered");
  await expect(evidence.getByTestId("event-effects")).toHaveText("1");
  await expect(evidence.getByTestId("event-duplicates")).toHaveText("1");
  await expect(evidence.locator(".timeline")).toContainText(
    "duplicate suppressed",
  );
  await page.getByRole("button", { name: "Redeliver event" }).click();
  await expect(evidence.locator(".status")).toHaveText("pending");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("delivered");
  await expect(evidence.getByTestId("event-effects")).toHaveText("1");
  await expect(evidence.getByTestId("event-duplicates")).toHaveText("2");
  await page
    .getByRole("button", { name: "Inspect payment", exact: true })
    .click();
  await expect(
    page.getByRole("dialog").locator(".detail-summary .status"),
  ).toHaveText("authorized");
  await expect(page.getByRole("dialog").locator(".posting")).toHaveCount(0);
});
test("retry exhaustion is visible and dead letter can be reviewed and redelivered", async ({
  page,
}) => {
  await page.getByRole("button", { name: "Prepare retry exhaustion" }).click();
  const evidence = page.getByRole("region", { name: "Delivery evidence" });
  await expect(evidence.getByTestId("event-effects")).toHaveText("0");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("retry");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".timeline")).toContainText("Attempt 2");
  await page.getByLabel("Advance logical clock").selectOption("2");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("dead");
  await expect(evidence.locator(".timeline")).toContainText("dead lettered");
  await expect(evidence.getByTestId("event-effects")).toHaveText("0");
  await page.getByRole("button", { name: "Redeliver event" }).click();
  await expect(evidence.locator(".status")).toHaveText("pending");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("delivered");
  await expect(evidence.getByTestId("event-effects")).toHaveText("1");
});
test("precommit interruption rolls back and the systems lab fits a phone viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Prepare crash before commit" })
    .click();
  const evidence = page.getByRole("region", { name: "Delivery evidence" });
  await expect(evidence.getByTestId("event-effects")).toHaveText("0");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("processing");
  await expect(evidence.getByTestId("event-effects")).toHaveText("0");
  await page.getByLabel("Advance logical clock").selectOption("2");
  await page.getByRole("button", { name: "Step selected event" }).click();
  await expect(evidence.locator(".status")).toHaveText("delivered");
  await expect(evidence.getByTestId("event-effects")).toHaveText("1");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
