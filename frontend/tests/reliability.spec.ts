import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import type { OutboxEvent } from "../src/lib/contracts";
import { mockSession, workspace, otherWorkspace, user } from "./fixtures";

const event: OutboxEvent = {
  id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
  repositoryId: "55555555-5555-4555-8555-555555555555",
  eventType: "WORKFLOW_SYNC",
  publicationStatus: "PUBLISHED",
  processingStatus: "FAILED",
  attempts: 3,
  totalAttempts: 3,
  replayCount: 0,
  errorCode: "GITHUB_SYNC_FAILED",
  createdAt: "2026-11-08T08:00:00Z",
  availableAt: "2026-11-08T08:00:02Z",
  processedAt: "2026-11-08T08:00:05Z",
};
const requestId = "ffffffff-ffff-4fff-8fff-ffffffffffff";

async function fixture(
  page: Page,
  options: { role?: string; conflict?: boolean; empty?: boolean } = {},
) {
  await mockSession(page, { role: options.role });
  let current = { ...event };
  const writes: unknown[] = [];
  await page.route("**/operations**", (route) => {
    const url = new URL(route.request().url());
    if (!url.pathname.includes("/api/backend/")) return route.fallback();
    if (url.pathname.endsWith("/retry")) {
      writes.push(route.request().postDataJSON());
      if (options.conflict)
        return route.fulfill({
          status: 409,
          json: {
            detail:
              "Only a failed task delivered to the DLQ with the current replay count can be retried.",
          },
        });
      current = { ...current, processingStatus: "PENDING", replayCount: 1, attempts: 0 };
      return route.fulfill({ json: current });
    }
    if (url.pathname.endsWith("/outbox"))
      return route.fulfill({
        json: {
          items: options.empty || url.pathname.includes(otherWorkspace.id) ? [] : [current],
          page: 0,
          size: 20,
          total: options.empty ? 0 : 1,
        },
      });
    return route.fulfill({
      json: options.empty
        ? []
        : [
            {
              eventType: current.eventType,
              status: current.processingStatus,
              count: 1,
              oldestAgeSeconds: 10,
            },
          ],
    });
  });
  await page.route("**/audit-logs?**", (route) =>
    route.fulfill({
      json: {
        items: options.empty
          ? []
          : [
              {
                id: "audit",
                actorUserId: user.id,
                action: "INCIDENT_STATUS_CHANGED",
                entityType: "INCIDENT",
                entityId: event.id,
                metadata: { fromStatus: "OPEN", toStatus: "INVESTIGATING" },
                requestId,
                createdAt: event.createdAt,
              },
            ],
        page: 0,
        size: 20,
        total: options.empty ? 0 : 1,
      },
    }),
  );
  return { writes };
}

test("owner confirms replay using the displayed generation and sees persisted intent", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/operations");
  await expect(page.getByRole("heading", { name: "Lịch sử tác vụ" })).toBeVisible();
  await page.getByRole("button", { name: /Thử lại tác vụ/ }).click();
  expect(state.writes).toHaveLength(0);
  await expect(page.getByRole("dialog")).toContainText("tối đa số lần");
  await page.getByRole("button", { name: "Xác nhận thử lại", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Đã xếp hàng thử lại" })).toBeVisible();
  expect(state.writes).toEqual([{ replayCount: 0 }]);
  await expect(page.getByRole("button", { name: /Thử lại tác vụ/ })).toHaveCount(0);
});

test("stale replay is never retried automatically", async ({ page }) => {
  const state = await fixture(page, { conflict: true });
  await page.goto("/operations");
  await page.getByRole("button", { name: /Thử lại tác vụ/ }).click();
  await page.getByRole("button", { name: "Xác nhận thử lại", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Tác vụ đã thay đổi" })).toBeVisible();
  expect(state.writes).toHaveLength(1);
  await expect(page.getByRole("button", { name: "Xác nhận thử lại", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Tải lại tác vụ", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("admin can inspect audit and trace IDs while workflow replay remains owner-only", async ({
  page,
}) => {
  await fixture(page, { role: "ADMIN" });
  await page.goto("/operations");
  await expect(page.getByText("OWNER có thể yêu cầu thử lại đồng bộ workflow.")).toBeVisible();
  await expect(page.getByRole("button", { name: /Thử lại tác vụ/ })).toHaveCount(0);
  await page.getByRole("button", { name: "Audit", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Đổi trạng thái sự cố" })).toBeVisible();
  await expect(page.getByText(requestId, { exact: true })).toBeVisible();
  await expect(
    page.getByText('{"fromStatus":"OPEN","toStatus":"INVESTIGATING"}', { exact: true }),
  ).toBeVisible();
});

test("member cannot open operational resources and does not receive its navigation link", async ({
  page,
}) => {
  await fixture(page, { role: "MEMBER" });
  let requests = 0;
  page.on("request", (request) => {
    if (
      request.url().includes("/api/backend/") &&
      (request.url().includes("/operations") || request.url().includes("/audit-logs"))
    )
      requests++;
  });
  await page.goto("/operations");
  await expect(page.getByText("Chỉ OWNER hoặc ADMIN được xem vận hành và audit.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Vận hành & audit", exact: true })).toHaveCount(0);
  expect(requests).toBe(0);
});

test("empty state and rate-limit errors remain explicit", async ({ page }) => {
  await fixture(page, { empty: true });
  await page.goto("/operations");
  await expect(page.getByRole("heading", { name: "Chưa có tác vụ", exact: true })).toBeVisible();
  await page.route("**/operations/outbox?**", (route) =>
    route.fulfill({
      status: 429,
      headers: { "Retry-After": "27", "X-Request-ID": requestId },
      json: { detail: "Too many requests." },
    }),
  );
  await page.getByRole("button", { name: "Làm mới", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "thử lại sau 27 giây" })).toBeVisible();
});

test("workspace switch removes previous operational records", async ({ page }) => {
  await fixture(page);
  await page.goto("/operations");
  await expect(page.getByText("GITHUB_SYNC_FAILED", { exact: true })).toBeVisible();
  await page.getByLabel("Workspace hiện tại", { exact: true }).selectOption(otherWorkspace.id);
  await expect(page.getByRole("heading", { name: "Chưa có tác vụ", exact: true })).toBeVisible();
  await expect(page.getByText("GITHUB_SYNC_FAILED", { exact: true })).toHaveCount(0);
});

for (const [detail, message] of [
  ["Rate limiting is temporarily unavailable.", "Bộ giới hạn yêu cầu tạm thời chưa sẵn sàng"],
  ["Storage is temporarily unavailable.", "Dịch vụ dữ liệu tạm thời chưa sẵn sàng"],
]) {
  test(`operational storage errors identify their cause: ${detail}`, async ({ page }) => {
    await fixture(page);
    let requests = 0;
    await page.route("**/operations/outbox?**", (route) => {
      requests++;
      return route.fulfill({ status: 503, json: { detail } });
    });
    await page.goto("/operations");
    const alert = page.getByRole("alert").filter({ hasText: message });
    await expect(alert).toBeVisible();
    await expect(alert).not.toContainText("Tích hợp GitHub");
    const beforeRetry = requests;
    await alert.getByRole("button", { name: "Thử lại", exact: true }).click();
    await expect.poll(() => requests).toBe(beforeRetry + 1);
  });
}

test("operational proxy paths are scoped and retries require same-origin POST", async ({
  request,
}) => {
  const base = `/api/backend/workspaces/${workspace.id}`;
  expect((await request.get(`${base}/audit-logs`)).status()).toBe(401);
  expect((await request.get(`${base}/operations/outbox`)).status()).toBe(401);
  expect((await request.get(`${base}/operations/outbox/${event.id}/retry`)).status()).toBe(404);
  expect(
    (
      await request.post(`${base}/operations/outbox/${event.id}/retry`, {
        headers: { Origin: "https://foreign.example" },
        data: { replayCount: 0 },
      })
    ).status(),
  ).toBe(403);
});

for (const width of [1440, 320]) {
  test(`operational history and audit fit and pass accessibility at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await fixture(page);
    await page.goto("/operations");
    await expect(page.getByText("GITHUB_SYNC_FAILED", { exact: true })).toBeVisible();
    const dir = path.resolve(process.cwd(), "../.verification/phase08");
    await mkdir(dir, { recursive: true });
    for (const tab of ["Tác vụ", "Audit"]) {
      await page.getByRole("button", { name: tab, exact: true }).click();
      await expect(
        page.getByRole("heading", {
          name: tab === "Audit" ? "Đổi trạng thái sự cố" : "Lịch sử tác vụ",
          exact: true,
        }),
      ).toBeVisible();
      expect(
        (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
          .violations,
      ).toEqual([]);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({
        path: path.join(dir, `operations-${tab === "Audit" ? "audit" : "tasks"}-${width}.png`),
        fullPage: true,
      });
    }
  });
}
