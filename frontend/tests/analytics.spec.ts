import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { mockSession, otherWorkspace } from "./fixtures";

async function dashboardFixture(page: Page, fail = false) {
  await mockSession(page);
  let unavailable = fail;
  const requests: number[] = [];
  await page.route("**/dashboard?**", (route) => {
    const url = new URL(route.request().url());
    const days = Number(url.searchParams.get("days"));
    requests.push(days);
    if (unavailable) {
      unavailable = false;
      return route.fulfill({ status: 503, json: { detail: "Unavailable" } });
    }
    const empty = url.pathname.includes(otherWorkspace.id);
    return route.fulfill({
      json: {
        from: "2026-10-12T00:00:00Z",
        to: "2026-11-10T08:00:00Z",
        timeZone: "UTC",
        daily: Array.from({ length: days }, (_, index) => ({
          date: new Date(Date.UTC(2026, 10, 11 - days + index)).toISOString().slice(0, 10),
          incidents: empty ? 0 : index % 3,
          successfulRuns: empty ? 0 : index % 5,
          failedRuns: empty ? 0 : index % 2,
        })),
        statuses: empty
          ? []
          : [
              { status: "OPEN", count: 4 },
              { status: "INVESTIGATING", count: 2 },
              { status: "RESOLVED", count: 8 },
            ],
      },
    });
  });
  return requests;
}

test("dashboard period requests backend aggregates and resets on workspace change", async ({
  page,
}) => {
  const requests = await dashboardFixture(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Lần chạy và sự cố theo ngày" })).toBeVisible();
  await page.getByLabel("Khoảng thời gian biểu đồ").selectOption("7");
  await expect.poll(() => requests.at(-1)).toBe(7);
  await page.getByText("Xem số liệu theo ngày", { exact: true }).click();
  await expect(
    page.getByRole("table", { name: "Lần chạy và sự cố theo ngày UTC" }).locator("tbody tr"),
  ).toHaveCount(7);
  await page.getByLabel("Workspace hiện tại", { exact: true }).selectOption(otherWorkspace.id);
  await expect(
    page.getByText("Chưa có hoạt động phù hợp trong khoảng thời gian này."),
  ).toBeVisible();
});

test("dashboard error can be retried while other overview sections remain available", async ({
  page,
}) => {
  await dashboardFixture(page);
  let unavailable = true;
  await page.route("**/dashboard?**", (route) =>
    unavailable
      ? route.fulfill({ status: 503, json: { detail: "Unavailable" } })
      : route.fallback(),
  );
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Sự cố gần đây" })).toBeVisible();
  await expect(page.getByRole("main").getByRole("alert")).toBeVisible();
  unavailable = false;
  await page.getByRole("main").getByRole("alert").getByRole("button", { name: "Thử lại" }).click();
  await expect(
    page.getByRole("figure", { name: "Biểu đồ số lần chạy và sự cố theo ngày" }),
  ).toBeVisible();
});

for (const [width, height] of [
  [1440, 900],
  [390, 844],
  [320, 812],
]) {
  test(`charts have accessible values and fit at ${width}px`, async ({ page }) => {
    await dashboardFixture(page);
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.setViewportSize({ width, height });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/");
    await expect(
      page.getByRole("figure", { name: "Biểu đồ số lần chạy và sự cố theo ngày" }),
    ).toBeVisible();
    await page.getByText("Xem số liệu theo ngày", { exact: true }).click();
    await expect(
      page.getByRole("table", { name: "Lần chạy và sự cố theo ngày UTC" }).locator("tbody tr"),
    ).toHaveCount(30);
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.getByText("Xem số liệu theo ngày", { exact: true }).click();
    await page.evaluate(() => window.scrollTo(0, 0));
    const dir = path.resolve(process.cwd(), "../.verification/analytics-ui");
    await mkdir(dir, { recursive: true });
    await page.screenshot({ path: path.join(dir, `dashboard-${width}.png`), fullPage: true });
    expect(errors).toEqual([]);
  });
}
