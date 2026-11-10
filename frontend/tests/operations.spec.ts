import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  analysis,
  analysisId,
  evidence,
  incidentId,
  mockOperations,
  run,
  runId,
} from "./operations-fixtures";
import { memberId, user, workspace } from "./fixtures";

const evidenceDir = path.resolve(process.cwd(), "../.verification/redesign");

test("workflow list links to its exact attempt and jobs", async ({ page }) => {
  await mockOperations(page);
  await page.goto("/runs");
  await expect(page.getByRole("link", { name: /CI \/ Build & Test/ })).toBeVisible();
  await page.getByRole("link", { name: /CI \/ Build & Test/ }).click();
  await expect(page).toHaveURL(new RegExp(`/runs/${run.repositoryId}/${runId}$`));
  await expect(page.getByRole("heading", { name: "Jobs của attempt 2" })).toBeVisible();
  await expect(page.getByText("build-backend", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Xem trên GitHub" })).toHaveAttribute(
    "href",
    run.htmlUrl!,
  );
});

test("sync reports queued work and sends a valid run ID/attempt", async ({ page }) => {
  await mockOperations(page);
  await page.goto("/runs");
  await page.getByRole("button", { name: "Đồng bộ lần chạy", exact: true }).click();
  await page.getByLabel("GitHub run ID", { exact: true }).fill("482");
  await page.getByLabel("Run attempt", { exact: true }).fill("2");
  const requested = page.waitForRequest(
    (request) => request.url().endsWith("/workflow-runs/sync") && request.method() === "POST",
  );
  await page.getByRole("button", { name: "Gửi yêu cầu đồng bộ" }).click();
  expect((await requested).postDataJSON()).toEqual({ githubRunId: 482, runAttempt: 2 });
  await expect(page.getByRole("status").filter({ hasText: "Đã xếp hàng" })).toBeVisible();
});

test("status mutation includes version and supports assignment without changing workspace scope", async ({
  page,
}) => {
  const fixture = await mockOperations(page);
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("combobox", { name: "Trạng thái", exact: true }).selectOption("RESOLVED");
  await page.getByRole("button", { name: "Lưu trạng thái", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Trạng thái", exact: true })).toHaveValue(
    "RESOLVED",
  );
  expect(fixture.updates[0]).toEqual({ status: "RESOLVED", version: 7 });
  await page.getByRole("combobox", { name: "Người phụ trách", exact: true }).selectOption(memberId);
  await page.getByRole("button", { name: "Lưu phân công", exact: true }).click();
  await expect.poll(() => fixture.updates.length).toBe(2);
  expect(fixture.updates[1]).toEqual({ assignedTo: memberId, version: 8 });
});

test("stale incident version requires reload and is never retried automatically", async ({
  page,
}) => {
  const fixture = await mockOperations(page, { conflict: true });
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("combobox", { name: "Trạng thái", exact: true }).selectOption("RESOLVED");
  await page.getByRole("button", { name: "Lưu trạng thái", exact: true }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "Sự cố vừa được cập nhật" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Lưu trạng thái", exact: true })).toBeDisabled();
  expect(fixture.updates).toHaveLength(1);
  await page.getByRole("button", { name: "Thử lại", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Trạng thái", exact: true })).toHaveValue(
    "RESOLVED",
  );
  expect(fixture.updates).toHaveLength(1);
});

test("unassigned member can read logs and reports but cannot mutate or request AI", async ({
  page,
}) => {
  await mockOperations(page, { role: "MEMBER", unassigned: true });
  await page.goto(`/incidents/${incidentId}`);
  await expect(page.getByRole("heading", { name: /Workflow run #482/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "Lưu trạng thái" })).toHaveCount(0);
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Nguyên nhân nghi ngờ" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Yêu cầu phân tích", exact: true })).toHaveCount(0);
});

test("AI evidence opens matching text across log chunks and never executes log HTML", async ({
  page,
}) => {
  await mockOperations(page);
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Nguyên nhân nghi ngờ" })).toBeVisible();
  await page.getByText("Mô hình & lượt gọi", { exact: true }).click();
  await expect(page.getByText("— / —", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Xem trong log đã che" }).click();
  await expect(page.locator(".log-content mark")).toHaveText(evidence);
  await expect(page.locator(".log-content")).toContainText(
    "<script>window.tracepilotInjected=true</script>",
  );
  expect(
    await page.evaluate(() => (window as unknown as Record<string, unknown>).tracepilotInjected),
  ).toBeUndefined();
});

test("AI request requires confirmation and preserves requestId on HTTP retry", async ({ page }) => {
  await mockOperations(page);
  const sent: string[] = [];
  const contexts: string[] = [];
  const pending = {
    ...analysis,
    id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    status: "PENDING",
    summary: null,
  };
  await page.route("**/analyses", (route) => {
    const id = route.request().postDataJSON().requestId;
    contexts.push(route.request().postDataJSON().additionalContext);
    sent.push(id);
    return sent.length === 1
      ? route.fulfill({ status: 503, json: { detail: "AI integration is not configured." } })
      : route.fulfill({ status: 202, json: pending });
  });
  await page.route(`**/analyses/${pending.id}`, (route) => route.fulfill({ json: pending }));
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await page.getByRole("button", { name: "Yêu cầu phân tích", exact: true }).click();
  expect(sent).toHaveLength(0);
  await expect(page.getByRole("dialog")).toContainText("nhà cung cấp AI");
  await page
    .getByLabel("Ngữ cảnh bổ sung (không bắt buộc)")
    .fill("./mvnw verify; Java 21; Ubuntu 24.04");
  await page.getByRole("button", { name: "Gửi yêu cầu phân tích", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "AI chưa được bật" })).toBeVisible();
  await page.getByRole("button", { name: "Gửi yêu cầu phân tích", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Đã gửi yêu cầu phân tích" }),
  ).toBeVisible();
  expect(sent).toHaveLength(2);
  expect(sent[0]).toBe(sent[1]);
  expect(contexts).toEqual([
    "./mvnw verify; Java 21; Ubuntu 24.04",
    "./mvnw verify; Java 21; Ubuntu 24.04",
  ]);
  await expect(page.getByRole("heading", { name: "Phân tích đang được xử lý" })).toBeVisible();
});

test("analysis shows the context snapshot belonging to the selected report", async ({ page }) => {
  await mockOperations(page);
  await page.route(`**/analyses/${analysis.id}`, (route) =>
    route.fulfill({
      json: { ...analysis, runContext: "Command: ./mvnw verify\nRuntime: Java 21 / Ubuntu 24.04" },
    }),
  );
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await page.getByText("Ngữ cảnh đã gửi cho lần phân tích này", { exact: true }).click();
  await expect(page.locator("pre.report-copy")).toContainText("Java 21 / Ubuntu 24.04");
});

test("notification read action persists after reload", async ({ page }) => {
  await mockOperations(page);
  await page.goto("/notifications");
  await page.getByRole("button", { name: /Đánh dấu đã đọc:/ }).click();
  await expect(page.locator(".read-label")).toHaveText("Đã đọc");
  await page.reload();
  await expect(page.locator(".read-label")).toHaveText("Đã đọc");
});

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 320, height: 812 },
]) {
  test(`design reference, incident tabs and accessibility at ${viewport.width}px`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await mockOperations(page);
    await mkdir(evidenceDir, { recursive: true });
    await page.goto("/");
    await expect(page.getByRole("link", { name: /Workflow run #482 thất bại/ })).toBeVisible();
    await page.screenshot({
      path: path.join(evidenceDir, `overview-${viewport.width}.png`),
      fullPage: true,
    });
    await page.goto(`/incidents/${incidentId}`);
    await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Nguyên nhân nghi ngờ" })).toBeVisible();
    await page.getByText("Mô hình & lượt gọi", { exact: true }).click();
    await expect(page.getByText("— / —", { exact: true })).toBeVisible();
    const axe = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    await writeFile(
      path.join(evidenceDir, `axe-analysis-${viewport.width}.json`),
      JSON.stringify(axe.violations, null, 2),
    );
    expect(
      axe.violations.map((violation) => ({
        id: violation.id,
        targets: violation.nodes.map((node) => node.target),
      })),
    ).toEqual([]);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: path.join(evidenceDir, `analysis-${viewport.width}.png`),
      fullPage: true,
    });
    await page.getByRole("button", { name: "Xem trong log đã che" }).click();
    await expect(page.locator(".log-content mark")).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: path.join(evidenceDir, `logs-${viewport.width}.png`),
      fullPage: true,
    });
  });
}
