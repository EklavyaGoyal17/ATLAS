import { expect, test } from "@playwright/test";

test("modal keyboard navigation contains focus and restores its opener", async ({
  page,
}) => {
  await page.goto("/");
  await page
    .getByLabel("Workspace token")
    .fill("atlas-local-development-token-change-me");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Your systems, understood." }),
  ).toBeVisible();

  const opener = page.getByRole("button", {
    name: "New authorization",
    exact: true,
  });
  await opener.focus();
  await page.keyboard.press("Enter");
  const authorization = page.getByRole("dialog", { name: "New authorization" });
  const amount = authorization.getByLabel("Amount in minor units");
  await expect(amount).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(authorization.getByLabel("Currency")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(authorization.getByLabel("Idempotency key")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(
    authorization.getByRole("button", {
      name: "Authorize payment",
      exact: true,
    }),
  ).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(
    authorization.getByRole("button", { name: "Close dialog" }),
  ).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(
    authorization.getByRole("button", {
      name: "Authorize payment",
      exact: true,
    }),
  ).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(authorization).toHaveCount(0);
  await expect(opener).toBeFocused();

  // Open, filter and activate navigation without a pointer. Dismissal must
  // restore the actual opener before the next keyboard operation.
  const jump = page.getByRole("button", { name: /Jump to/ });
  await jump.focus();
  await page.keyboard.press("Enter");
  const palette = page.getByRole("dialog", { name: "Jump to a workspace" });
  await expect(palette.getByLabel("Search navigation")).toBeFocused();
  await page.keyboard.type("field");
  await page.keyboard.press("Tab");
  await expect(
    palette.getByRole("button", { name: "Engineering field guide" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(palette).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "The engineering field guide" }),
  ).toBeVisible();
  await expect(jump).toBeFocused();
});
