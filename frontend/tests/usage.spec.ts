import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import type { UsageDashboard, UsageTotals } from "../src/lib/usage";
import { mockSession, otherWorkspace, workspace } from "./fixtures";

const empty: UsageTotals = {
  calls: 0,
  succeeded: 0,
  failed: 0,
  unknown: 0,
  inputTokens: 0,
  outputTokens: 0,
  inputSamples: 0,
  outputSamples: 0,
  knownEstimatedCostUsd: 0,
  pricedCalls: 0,
};
const chat: UsageTotals = {
  calls: 1,
  succeeded: 1,
  failed: 0,
  unknown: 0,
  inputTokens: 100,
  outputTokens: 50,
  inputSamples: 1,
  outputSamples: 1,
  knownEstimatedCostUsd: 0.0005,
  pricedCalls: 1,
};
const embedding: UsageTotals = {
  ...chat,
  inputTokens: 20,
  outputTokens: 0,
  knownEstimatedCostUsd: 0.00001,
};
const unknown: UsageTotals = {
  ...empty,
  calls: 1,
  unknown: 1,
  inputTokens: null,
  outputTokens: null,
  knownEstimatedCostUsd: null,
};

async function usageFixture(page: Page, options: { fail?: boolean; empty?: boolean } = {}) {
  await mockSession(page);
  let unavailable = Boolean(options.fail);
  const requests: URLSearchParams[] = [];
  await page.route("**/ai-usage?**", (route) => {
    const url = new URL(route.request().url());
    requests.push(url.searchParams);
    if (unavailable) return route.fulfill({ status: 503, json: { detail: "Unavailable" } });
    const isEmpty = options.empty || url.pathname.includes(otherWorkspace.id);
    const days = Number(url.searchParams.get("days"));
    const byModel = [
      { provider: "OPENAI", operation: "CHAT", model: "chat-fixture", usage: chat },
      { provider: "OPENAI", operation: "CHAT", model: "unknown-fixture", usage: unknown },
      {
        provider: "OPENAI",
        operation: "EMBEDDING",
        model: "text-embedding-3-small",
        usage: embedding,
      },
    ].filter(
      (item) =>
        (!url.searchParams.get("model") || item.model === url.searchParams.get("model")) &&
        (!url.searchParams.get("operation") ||
          item.operation === url.searchParams.get("operation")),
    );
    const totals = isEmpty
      ? empty
      : url.searchParams.get("model") === "unknown-fixture"
        ? unknown
        : url.searchParams.get("operation") === "EMBEDDING"
          ? embedding
          : {
              calls: 3,
              succeeded: 2,
              failed: 0,
              unknown: 1,
              inputTokens: 120,
              outputTokens: 50,
              inputSamples: 2,
              outputSamples: 2,
              knownEstimatedCostUsd: 0.00051,
              pricedCalls: 2,
            };
    const data: UsageDashboard = {
      from: "2026-10-12T00:00:00Z",
      to: "2026-11-10T08:00:00Z",
      timeZone: "UTC",
      totals,
      daily: Array.from({ length: days }, (_, index) => ({
        date: new Date(Date.UTC(2026, 10, 11 - days + index)).toISOString().slice(0, 10),
        usage: index === days - 1 ? totals : empty,
      })),
      byModel: isEmpty ? [] : byModel,
      models: ["chat-fixture", "unknown-fixture", "text-embedding-3-small"],
    };
    return route.fulfill({ json: data });
  });
  return {
    requests,
    recover: () => {
      unavailable = false;
    },
  };
}

test("usage keeps missing measurements distinct from zero and filters model and operation", async ({
  page,
}) => {
  const state = await usageFixture(page);
  await page.goto("/usage");
  await expect(page.locator(".usage-summary").getByText("120", { exact: true })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("không được coi là 0");
  await page.getByLabel("Mô hình sử dụng").selectOption("unknown-fixture");
  await expect(page.locator(".usage-summary").getByText("Chưa có", { exact: true })).toHaveCount(3);
  await page.getByLabel("Loại lượt gọi").selectOption("EMBEDDING");
  await expect.poll(() => state.requests.at(-1)?.get("operation")).toBe("EMBEDDING");
  await expect(page.locator(".usage-summary").getByText("20", { exact: true })).toBeVisible();
  await expect(page.locator(".usage-summary").getByText("0", { exact: true })).toBeVisible();
  await page.getByLabel("Khoảng thời gian tiêu thụ").selectOption("7");
  await expect.poll(() => state.requests.at(-1)?.get("days")).toBe("7");
  await page.getByLabel("Workspace hiện tại", { exact: true }).selectOption(otherWorkspace.id);
  await expect(
    page.getByRole("heading", { name: "Chưa có lượt gọi AI trong phạm vi này" }),
  ).toBeVisible();
  await expect(page.getByRole("figure")).toHaveCount(0);
});

test("usage retries backend failure and shows an honest empty state", async ({ page }) => {
  const state = await usageFixture(page, { fail: true, empty: true });
  await page.goto("/usage");
  await expect(page.getByRole("main").getByRole("alert")).toBeVisible();
  state.recover();
  await page.getByRole("main").getByRole("button", { name: "Thử lại" }).click();
  await expect(
    page.getByRole("heading", { name: "Chưa có lượt gọi AI trong phạm vi này" }),
  ).toBeVisible();
});

test("BFF analytics endpoints expose only read methods", async ({ request, baseURL }) => {
  for (const endpoint of ["dashboard", "ai-usage"]) {
    const url = `/api/backend/workspaces/${workspace.id}/${endpoint}`;
    expect((await request.get(url)).status()).toBe(401);
    expect((await request.post(url, { headers: { Origin: baseURL! }, data: {} })).status()).toBe(
      404,
    );
    expect(
      (
        await request.post(url, { headers: { Origin: "https://foreign.example" }, data: {} })
      ).status(),
    ).toBe(403);
  }
});

for (const [width, height] of [
  [1440, 900],
  [390, 844],
  [320, 812],
]) {
  test(`usage charts, partial measurements and tables are accessible at ${width}px`, async ({
    page,
  }) => {
    await usageFixture(page);
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.setViewportSize({ width, height });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/usage");
    await expect(
      page.getByRole("figure", { name: "Biểu đồ token đầu vào và đầu ra" }),
    ).toBeVisible();
    await page.getByText("Xem token và chi phí từng ngày", { exact: true }).click();
    await expect(
      page
        .getByRole("table", { name: "Token, lượt gọi và chi phí theo ngày UTC" })
        .locator("tbody tr"),
    ).toHaveCount(30);
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.getByText("Xem token và chi phí từng ngày", { exact: true }).click();
    await page.evaluate(() => window.scrollTo(0, 0));
    const dir = path.resolve(process.cwd(), "../.verification/analytics-ui");
    await mkdir(dir, { recursive: true });
    await page.screenshot({ path: path.join(dir, `usage-${width}.png`), fullPage: true });
    expect(errors).toEqual([]);
  });
}
