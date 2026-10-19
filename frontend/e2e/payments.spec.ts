import { test, expect } from "@playwright/test";
const token =
  process.env.ATLAS_E2E_TOKEN ?? "atlas-local-development-token-change-me";
test("real authorization, response replay, capture, refund, ledger and reload", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByLabel("Workspace token").fill(token);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Your systems, understood." }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "New authorization", exact: true })
    .click();
  await page.getByLabel("Amount in minor units").fill("12500");
  const key = await page.getByLabel("Idempotency key").inputValue();
  expect(key.length).toBeGreaterThan(8);
  await page
    .getByRole("button", { name: "Authorize payment", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("authorized", { exact: true }).first(),
  ).toBeVisible();
  const id = (await dialog.locator(".detail-summary code").textContent())!;
  await page.getByRole("button", { name: "Close dialog" }).click();
  await page.getByRole("button", { name: "Replay last authorization" }).click();
  await expect(page.getByRole("status")).toContainText("Safely replayed");
  await expect(dialog.locator(".detail-summary code")).toHaveText(id);
  await page.getByRole("button", { name: "Capture payment" }).click();
  await expect(
    dialog.getByText("captured", { exact: true }).first(),
  ).toBeVisible();
  await expect(dialog.locator(".posting")).toHaveCount(1);
  await page.getByLabel("Refund in minor units").fill("2500");
  await page.getByRole("button", { name: "Refund amount" }).click();
  await expect(dialog.locator(".posting")).toHaveCount(2);
  await expect(
    dialog.getByText(
      "Refund of 2500 minor units committed with ledger posting.",
    ),
  ).toBeVisible();
  await page.getByRole("button", { name: "Close dialog" }).click();
  await page.getByRole("button", { name: "Ledger & reconciliation" }).click();
  await expect(page.locator(".currency-card")).toHaveCount(3);
  await expect(page.locator(".currency-card .balanced")).toHaveCount(3);
  await page.reload();
  await expect(page.getByLabel("Workspace token")).toBeVisible();
  await page.getByLabel("Workspace token").fill(token);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Your systems, understood." }),
  ).toBeVisible();
  const detail = await page.request.get(
    `http://127.0.0.1:8080/api/payments/${id}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(detail.status()).toBe(200);
  expect((await detail.json()).payment.refundedMinor).toBe(2500);
  expect(errors).toEqual([]);
});
test("connection errors, keyboard palette, reversal, and mobile layout", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page
    .getByLabel("Workspace token")
    .fill("wrong-token-with-at-least-32-characters");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "valid ATLAS bearer token",
  );
  await page.getByLabel("Workspace token").fill(token);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Your systems, understood." }),
  ).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page
    .getByRole("button", { name: "New authorization", exact: true })
    .click();
  await page.getByLabel("Amount in minor units").fill("100");
  await page
    .getByRole("button", { name: "Authorize payment", exact: true })
    .click();
  await page.getByRole("button", { name: "Reverse authorization" }).click();
  await expect(
    page.getByRole("dialog").getByText("reversed", { exact: true }).first(),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await page.keyboard.press("Control+k");
  await page.getByLabel("Search navigation").fill("field");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Engineering field guide" })
    .click();
  await expect(
    page.getByRole("heading", { name: "The engineering field guide" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
