import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import type { AnalysisFeedback, QualityDashboard } from "../src/lib/contracts";
import { mockOperations, analysis, incidentId } from "./operations-fixtures";
import { otherWorkspace, workspace } from "./fixtures";

const dashboard: QualityDashboard = {
  from: "2026-10-09T12:00:00Z",
  to: "2026-11-08T12:00:00Z",
  analyses: {
    total: 4,
    pending: 1,
    running: 0,
    succeeded: 2,
    failed: 1,
    successRate: 2 / 3,
    averageLatencyMs: 3000,
    p95LatencyMs: 4800,
  },
  feedback: {
    votes: 2,
    helpful: 1,
    unhelpful: 1,
    reviewedAnalyses: 1,
    helpfulRate: 0.5,
    reviewCoverage: 0.5,
  },
  reasons: [
    { reason: "ACTIONABLE_FIX", helpful: true, count: 1 },
    { reason: "INCORRECT_DIAGNOSIS", helpful: false, count: 1 },
  ],
  usage: [
    {
      operation: "CHAT",
      provider: "openai",
      model: "fixture-model",
      calls: 2,
      succeeded: 1,
      failed: 0,
      unknown: 1,
      inputTokens: 100,
      outputTokens: 20,
      inputTokenSamples: 1,
      outputTokenSamples: 1,
      knownEstimatedCostUsd: 0.01,
      pricedCalls: 1,
      durationSamples: 1,
      averageDurationMs: 500,
      p95DurationMs: 500,
    },
  ],
  errors: [{ code: "AI_INVALID_RESPONSE", count: 1 }],
};

async function fixture(page: Page, conflict = false) {
  await mockOperations(page, { role: "MEMBER", unassigned: true });
  let feedback: AnalysisFeedback | null = null;
  const writes: unknown[] = [];
  await page.route("**/quality?**", (route) => route.fulfill({ json: dashboard }));
  await page.route("**/feedback", (route) => {
    if (route.request().method() === "GET") return route.fulfill({ json: { feedback } });
    const body = route.request().postDataJSON();
    writes.push(body);
    if (conflict && writes.length === 1) {
      feedback = {
        id: "feedback-fixture",
        analysisId: analysis.id,
        helpful: false,
        reason: "OTHER",
        comment: "A newer vote",
        version: 2,
        createdAt: analysis.createdAt,
        updatedAt: analysis.createdAt,
      };
      return route.fulfill({
        status: 409,
        json: { detail: "Feedback changed. Reload before updating." },
      });
    }
    feedback = {
      ...body,
      id: "feedback-fixture",
      analysisId: analysis.id,
      version: body.version + 1,
      createdAt: analysis.createdAt,
      updatedAt: analysis.createdAt,
    };
    return route.fulfill({ json: feedback });
  });
  return { writes };
}

async function openFeedback(page: Page) {
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Báo cáo này có hữu ích?" })).toBeVisible();
  await expect(page.getByRole("radio", { name: "Hữu ích", exact: true })).toBeVisible();
}

test("unassigned member can save and revise their own feedback with its version", async ({
  page,
}) => {
  const state = await fixture(page);
  await openFeedback(page);
  await expect(page.getByRole("button", { name: "Lưu đánh giá" })).toBeDisabled();
  await page.getByRole("radio", { name: "Hữu ích", exact: true }).check();
  await page.getByLabel("Lý do đánh giá", { exact: true }).selectOption("ACTIONABLE_FIX");
  await page.getByLabel("Nhận xét (không bắt buộc)", { exact: true }).fill("Verified locally");
  await page.getByRole("button", { name: "Lưu đánh giá" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Đã lưu đánh giá" })).toBeVisible();
  await expect.poll(() => state.writes.length).toBe(1);
  expect(state.writes[0]).toEqual({
    helpful: true,
    reason: "ACTIONABLE_FIX",
    comment: "Verified locally",
    version: 0,
  });
  await page.reload();
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await expect(page.getByLabel("Lý do đánh giá", { exact: true })).toHaveValue("ACTIONABLE_FIX");
  await page.getByRole("radio", { name: "Chưa hữu ích", exact: true }).check();
  await page.getByLabel("Lý do đánh giá", { exact: true }).selectOption("INSUFFICIENT_EVIDENCE");
  await page.getByRole("button", { name: "Lưu đánh giá" }).click();
  await expect.poll(() => state.writes.length).toBe(2);
  expect(state.writes[1]).toMatchObject({
    helpful: false,
    reason: "INSUFFICIENT_EVIDENCE",
    version: 1,
  });
});

test("feedback conflict blocks submission until the newer vote is loaded", async ({ page }) => {
  const state = await fixture(page, true);
  await openFeedback(page);
  await page.getByRole("radio", { name: "Hữu ích", exact: true }).check();
  await page.getByRole("button", { name: "Lưu đánh giá" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Đánh giá vừa thay đổi" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Lưu đánh giá" })).toBeDisabled();
  expect(state.writes).toHaveLength(1);
  await page.getByRole("button", { name: "Tải lại đánh giá" }).click();
  await expect(page.getByLabel("Nhận xét (không bắt buộc)", { exact: true })).toHaveValue(
    "A newer vote",
  );
  await page.getByRole("radio", { name: "Hữu ích", exact: true }).check();
  await page.getByRole("button", { name: "Lưu đánh giá" }).click();
  await expect.poll(() => state.writes.length).toBe(2);
  expect(state.writes[1]).toMatchObject({ version: 2 });
});

test("unsaved feedback survives temporary realtime read errors but is hidden after access denial", async ({
  page,
}) => {
  const state = await fixture(page);
  await openFeedback(page);
  await page.getByRole("radio", { name: "Hữu ích", exact: true }).check();
  await page.getByLabel("Nhận xét (không bắt buộc)", { exact: true }).fill("Nhận xét chưa lưu");
  let readStatus = 503;
  await page.route("**/api/backend/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (
      readStatus &&
      route.request().method() === "GET" &&
      path.includes(`/incidents/${incidentId}`)
    ) {
      return route.fulfill({ status: readStatus, json: { detail: "Unavailable" } });
    }
    return route.fallback();
  });
  await page.evaluate(async (workspaceId) => {
    window.dispatchEvent(new CustomEvent("workspace-updated", { detail: workspaceId }));
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
  }, workspace.id);
  await expect(page.getByLabel("Nhận xét (không bắt buộc)", { exact: true })).toHaveValue(
    "Nhận xét chưa lưu",
  );
  readStatus = 0;
  await page.getByRole("button", { name: "Lưu đánh giá" }).click();
  await expect.poll(() => state.writes.length).toBe(1);
  expect(state.writes[0]).toMatchObject({ comment: "Nhận xét chưa lưu", version: 0 });
  readStatus = 403;
  await page.evaluate(
    (workspaceId) =>
      window.dispatchEvent(new CustomEvent("workspace-updated", { detail: workspaceId })),
    workspace.id,
  );
  await expect(page.getByLabel("Nhận xét (không bắt buộc)", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("main").getByRole("alert")).toContainText("Bạn không có quyền");
});

test("quality dashboard keeps success, helpfulness and missing cost coverage separate", async ({
  page,
}) => {
  await fixture(page);
  await page.goto("/quality");
  await expect(page.getByRole("heading", { name: "Chất lượng AI", exact: true })).toBeVisible();
  await expect(page.getByText("66,7%", { exact: true })).toBeVisible();
  await expect(page.getByText("50%", { exact: true })).toHaveCount(2);
  await expect(page.getByText("$0.01", { exact: false })).toBeVisible();
  await expect(page.getByText("(1/2 lượt có chi phí)", { exact: true })).toBeVisible();
  const next = page.waitForRequest((request) => request.url().endsWith("/quality?days=90"));
  await page.getByLabel("Khoảng thời gian", { exact: true }).selectOption("90");
  await next;
});

test("empty metrics show unknown rates and changing workspace removes previous metrics", async ({
  page,
}) => {
  await fixture(page);
  await page.route(`**/workspaces/${otherWorkspace.id}/quality?**`, (route) =>
    route.fulfill({
      json: {
        ...dashboard,
        analyses: {
          total: 0,
          pending: 0,
          running: 0,
          succeeded: 0,
          failed: 0,
          successRate: null,
          averageLatencyMs: null,
          p95LatencyMs: null,
        },
        feedback: {
          votes: 0,
          helpful: 0,
          unhelpful: 0,
          reviewedAnalyses: 0,
          helpfulRate: null,
          reviewCoverage: null,
        },
        reasons: [],
        errors: [],
        usage: [],
      },
    }),
  );
  await page.goto("/quality");
  await expect(page.getByText("66,7%", { exact: true })).toBeVisible();
  await page.getByLabel("Workspace hiện tại", { exact: true }).selectOption(otherWorkspace.id);
  await expect(
    page.getByText("Chưa có lượt gọi AI trong khoảng này.", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("66,7%", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Chưa có dữ liệu", { exact: true })).toHaveCount(4);
});

test("quality errors are visible and retry reloads instead of inventing zero metrics", async ({
  page,
}) => {
  await fixture(page);
  let failed = true;
  await page.route("**/quality?**", (route) =>
    failed ? route.fulfill({ status: 502, json: {} }) : route.fulfill({ json: dashboard }),
  );
  await page.goto("/quality");
  await expect(
    page.getByRole("alert").filter({ hasText: "Không kết nối được dịch vụ" }),
  ).toBeVisible();
  await expect(page.getByText("66,7%", { exact: true })).toHaveCount(0);
  failed = false;
  await page.getByRole("button", { name: "Thử lại", exact: true }).click();
  await expect(page.getByText("66,7%", { exact: true })).toBeVisible();
});

test("BFF permits only scoped feedback methods and enforces same-origin PUT", async ({
  request,
}) => {
  const root = `/api/backend/workspaces/${workspace.id}`;
  const feedback = `${root}/incidents/${incidentId}/analyses/${analysis.id}/feedback`;
  expect((await request.get(`${root}/quality?days=7`)).status()).toBe(401);
  expect((await request.get(feedback)).status()).toBe(401);
  expect(
    (
      await request.put(feedback, { headers: { Origin: "https://foreign.example" }, data: {} })
    ).status(),
  ).toBe(403);
  const origin = process.env.FRONTEND_URL ?? "http://127.0.0.1:3000";
  expect((await request.put(feedback, { headers: { Origin: origin }, data: {} })).status()).toBe(
    401,
  );
  expect(
    (await request.put(`${root}/quality`, { headers: { Origin: origin }, data: {} })).status(),
  ).toBe(404);
});

for (const width of [1440, 320]) {
  test(`quality and feedback are accessible and fit at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await fixture(page);
    await page.goto("/quality");
    await expect(page.getByText("66,7%", { exact: true })).toBeVisible();
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    const dir = path.resolve(process.cwd(), "../.verification/phase07");
    await mkdir(dir, { recursive: true });
    await page.screenshot({ path: path.join(dir, `quality-${width}.png`), fullPage: true });
    await openFeedback(page);
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: path.join(dir, `feedback-${width}.png`), fullPage: true });
  });
}

test("sidebar can reach logout after adding quality navigation on a short screen", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 560 });
  await fixture(page);
  await page.route("**/api/auth/logout", (route) => route.fulfill({ status: 204 }));
  await page.goto("/quality");
  await expect(page.getByText("66,7%", { exact: true })).toBeVisible();
  const logout = page.getByRole("button", { name: "Đăng xuất", exact: true });
  await logout.scrollIntoViewIfNeeded();
  await expect(logout).toBeInViewport();
  await logout.click();
  await expect(page).toHaveURL(/\/login$/);
});
