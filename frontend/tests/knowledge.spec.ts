import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import type { KnowledgeArticle, AnalysisEvidence } from "../src/lib/contracts";
import { mockSession, otherWorkspace, user, workspace, memberId } from "./fixtures";
import { analysis, incident, incidentId, mockOperations } from "./operations-fixtures";

const articleId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const article: KnowledgeArticle = {
  id: articleId,
  workspaceId: workspace.id,
  sourceIncidentId: null,
  title: "EADDRINUSE khi khởi động dịch vụ",
  rootCause: "Một tiến trình cũ vẫn giữ cổng của ứng dụng.",
  resolution:
    "Xác định tiến trình giữ cổng. Dừng tiến trình cũ rồi chạy lại dịch vụ.\nKiểm tra health sau khi khởi động.",
  status: "DRAFT",
  indexStatus: "NONE",
  version: 4,
  contentVersion: 2,
  createdBy: user.id,
  approvedBy: null,
  approvedAt: null,
  indexErrorCode: null,
  createdAt: "2026-11-09T08:00:00Z",
  updatedAt: "2026-11-09T08:10:00Z",
};

async function fixture(
  page: Page,
  options: {
    role?: string;
    empty?: boolean;
    conflict?: boolean;
    approvalFailure?: boolean;
    foreignDraft?: boolean;
    delay?: boolean;
  } = {},
) {
  await mockSession(page, { role: options.role });
  let current = { ...article, createdBy: options.foreignDraft ? memberId : user.id };
  let savedSearch: {
    search: {
      id: string;
      query: string;
      mode: string;
      topK: number;
      resultCount: number;
      createdAt: string;
    };
    result: Record<string, unknown>;
  } | null = null;
  const writes: { method: string; path: string; body: Record<string, unknown> }[] = [];
  await page.route("**/knowledge-articles**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const endpoint = url.pathname;
    if (endpoint.includes("/search-history")) {
      if (request.method() === "DELETE") {
        savedSearch = null;
        return route.fulfill({ status: 204 });
      }
      if (endpoint.endsWith("/search-history"))
        return route.fulfill({
          json: {
            items: savedSearch ? [savedSearch.search] : [],
            page: 0,
            size: 10,
            total: savedSearch ? 1 : 0,
          },
        });
      return route.fulfill({ json: savedSearch });
    }
    if (options.delay) await new Promise((resolve) => setTimeout(resolve, 600));
    if (endpoint.includes(otherWorkspace.id))
      return route.fulfill({ json: { items: [], page: 0, size: 20, total: 0 } });
    if (request.method() === "GET") {
      return route.fulfill({
        json: endpoint.endsWith("/knowledge-articles")
          ? {
              items: options.empty ? [] : [current],
              page: Number(url.searchParams.get("page") ?? 0),
              size: 20,
              total: options.empty ? 0 : 1,
            }
          : current,
      });
    }
    const body = request.postDataJSON();
    writes.push({ method: request.method(), path: endpoint, body });
    if (endpoint.endsWith("/search")) {
      const result = {
        items: [
          {
            id: "chunk",
            articleId,
            articleVersion: 2,
            content: current.resolution,
            contentSha256: "a".repeat(64),
            similarity: 0.812,
          },
        ],
        mode: body.mode,
        embeddingModel: "fixture-embedding",
      };
      savedSearch = {
        search: {
          id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
          query: body.query,
          mode: body.mode,
          topK: body.topK,
          resultCount: 1,
          createdAt: article.createdAt,
        },
        result,
      };
      return route.fulfill({ json: result });
    }
    if (endpoint.endsWith("/approve")) {
      current = {
        ...current,
        status: "APPROVED",
        indexStatus: options.approvalFailure ? "FAILED" : "READY",
        indexErrorCode: options.approvalFailure ? "AI_TIMEOUT" : null,
        version: current.version + 1,
      };
      return options.approvalFailure
        ? route.fulfill({
            status: 502,
            json: { detail: "Knowledge indexing failed. Reload before retrying approval." },
          })
        : route.fulfill({ json: current });
    }
    if (endpoint.endsWith("/archive")) {
      current = {
        ...current,
        status: "ARCHIVED",
        indexStatus: "NONE",
        version: current.version + 1,
      };
      return route.fulfill({ json: current });
    }
    if (options.conflict && request.method() === "PATCH") {
      current = { ...current, title: "Nội dung mới từ người khác", version: 5 };
      return route.fulfill({
        status: 409,
        json: { detail: "Knowledge changed. Reload before updating." },
      });
    }
    current = {
      ...current,
      ...body,
      status: "DRAFT",
      indexStatus: "NONE",
      version: current.version + 1,
      contentVersion: current.contentVersion + 1,
    };
    return route.fulfill({ status: request.method() === "POST" ? 201 : 200, json: current });
  });
  return { writes };
}

test("create a draft from human-verified text and show the stored response", async ({ page }) => {
  const state = await fixture(page, { empty: true });
  await page.goto("/knowledge");
  await expect(page.getByRole("heading", { name: "Chưa có bài viết" })).toBeVisible();
  await page.getByRole("link", { name: "Viết bài mới", exact: true }).click();
  await expect(page.getByRole("button", { name: "Lưu bản nháp" })).toBeDisabled();
  await page.getByLabel("Tiêu đề", { exact: true }).fill("Lỗi build đã xác nhận");
  await page
    .getByLabel("Nguyên nhân đã xác nhận", { exact: false })
    .fill("Runner chưa dùng JDK 21");
  await page
    .getByLabel("Cách khắc phục đã kiểm tra", { exact: false })
    .fill("Đặt JDK 21 rồi chạy lại Maven verify");
  await page.getByRole("button", { name: "Lưu bản nháp" }).click();
  await expect(page).toHaveURL(new RegExp(`/knowledge/${articleId}$`));
  await expect(page.getByRole("heading", { name: "Lỗi build đã xác nhận" })).toBeVisible();
  expect(state.writes[0]).toMatchObject({
    method: "POST",
    body: {
      sourceIncidentId: null,
      title: "Lỗi build đã xác nhận",
      rootCause: "Runner chưa dùng JDK 21",
    },
  });
});

test("prefill an editable AI draft from an unresolved incident without publishing", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.route(`**/incidents/${incidentId}`, (route) => route.fulfill({ json: incident }));
  await page.route(`**/analyses/${analysis.id}`, (route) => route.fulfill({ json: analysis }));
  await page.goto(`/knowledge/new?incidentId=${incidentId}&analysisId=${analysis.id}`);
  await expect(page.getByLabel("Tiêu đề", { exact: true })).toHaveValue(incident.title);
  await expect(page.getByLabel("Nguyên nhân cần kiểm tra", { exact: false })).toHaveValue(
    analysis.suspectedRootCause!,
  );
  await expect(page.getByLabel("Cách khắc phục đề xuất", { exact: false })).toHaveValue(
    analysis.suggestedFix!,
  );
  expect(state.writes).toHaveLength(0);
  await page.getByLabel("Tiêu đề", { exact: true }).fill("Đề xuất đã xem lại");
  await page.getByRole("button", { name: "Lưu bản nháp" }).click();
  await expect(page).toHaveURL(new RegExp(`/knowledge/${articleId}$`));
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].body).toMatchObject({
    sourceIncidentId: incidentId,
    sourceAnalysisId: analysis.id,
    title: "Đề xuất đã xem lại",
  });
});

test("approval requires confirmation and archive keeps its current version", async ({ page }) => {
  const state = await fixture(page);
  await page.goto(`/knowledge/${articleId}`);
  await page.getByRole("button", { name: "Duyệt bài viết", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("nhà cung cấp AI");
  expect(state.writes).toHaveLength(0);
  await page.getByRole("button", { name: "Xác nhận duyệt" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Đã duyệt bài viết" })).toBeVisible();
  await expect(page.getByText("Sẵn sàng", { exact: true })).toBeVisible();
  expect(state.writes[0].body).toEqual({ version: 4 });
  await expect(page.getByRole("button", { name: "Duyệt bài viết", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Lưu trữ bài", exact: true }).click();
  await page.getByRole("button", { name: "Xác nhận lưu trữ" }).click();
  await expect(page.getByText("Đã lưu trữ", { exact: true })).toBeVisible();
  expect(state.writes[1].body).toEqual({ version: 5 });
});

test("approval failure blocks another mutation until a fresh article is loaded", async ({
  page,
}) => {
  const state = await fixture(page, { approvalFailure: true });
  await page.goto(`/knowledge/${articleId}`);
  await page.getByRole("button", { name: "Duyệt bài viết", exact: true }).click();
  await page.getByRole("button", { name: "Xác nhận duyệt" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Tạo chỉ mục chưa thành công",
  );
  await expect(page.getByRole("button", { name: "Duyệt bài viết", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Tải lại bài viết" }).click();
  await expect(page.getByText("AI_TIMEOUT", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Thử tạo lại chỉ mục" }).click();
  await page.getByRole("button", { name: "Xác nhận duyệt" }).click();
  await expect.poll(() => state.writes.length).toBe(2);
  expect(state.writes[1].body).toEqual({ version: 5 });
});

test("edit conflict preserves input and requires explicitly loading the newer revision", async ({
  page,
}) => {
  const state = await fixture(page, { role: "MEMBER", conflict: true });
  await page.goto(`/knowledge/${articleId}`);
  await expect(page.getByRole("button", { name: "Duyệt bài viết", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Chỉnh sửa bài" }).click();
  await page.getByLabel("Tiêu đề", { exact: true }).fill("Bản sửa của tôi");
  await page.getByRole("button", { name: "Lưu bản nháp" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("Bài viết vừa thay đổi");
  await expect(page.getByLabel("Tiêu đề", { exact: true })).toHaveValue("Bản sửa của tôi");
  await expect(page.getByRole("button", { name: "Lưu bản nháp" })).toBeDisabled();
  expect(state.writes[0].body.version).toBe(4);
  await page.getByRole("button", { name: "Tải lại bài viết", exact: true }).first().click();
  await expect(page.getByRole("heading", { name: "Nội dung mới từ người khác" })).toBeVisible();
});

test("member can read another author's draft without management controls", async ({ page }) => {
  await fixture(page, { role: "MEMBER", foreignDraft: true });
  await page.goto(`/knowledge/${articleId}`);
  await expect(page.getByRole("heading", { name: article.title })).toBeVisible();
  for (const name of ["Chỉnh sửa bài", "Duyệt bài viết", "Lưu trữ bài"])
    await expect(page.getByRole("button", { name, exact: true })).toHaveCount(0);
});

test("search sends only after consent and clears results on workspace change", async ({ page }) => {
  const state = await fixture(page);
  await page.goto("/knowledge");
  await page.getByLabel("Mô tả lỗi").fill("Không bind được socket");
  await expect(page.getByRole("button", { name: "Tìm trong kho kiến thức" })).toBeDisabled();
  expect(state.writes).toHaveLength(0);
  await page.getByRole("checkbox").check();
  await page.getByRole("combobox", { name: "Cách tìm", exact: true }).selectOption("EXACT");
  await page.getByRole("button", { name: "Tìm trong kho kiến thức" }).click();
  await expect(page.getByRole("link", { name: "Mở bài viết", exact: true })).toBeVisible();
  expect(state.writes[0].body).toEqual({ query: "Không bind được socket", topK: 3, mode: "EXACT" });
  await page.getByLabel("Workspace hiện tại", { exact: true }).selectOption(otherWorkspace.id);
  await expect(page.getByRole("heading", { name: "Chưa có bài viết" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Mở bài viết", exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Mô tả lỗi")).toHaveValue("");
});

test("successful lookup is automatically saved and replayed without another provider call", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/knowledge");
  await page.getByLabel("Mô tả lỗi").fill("Socket occupied");
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Tìm trong kho kiến thức" }).click();
  await expect(page.getByRole("button", { name: "Socket occupied", exact: true })).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "Socket occupied", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Kết quả đã lưu: Socket occupied" }),
  ).toBeVisible();
  expect(state.writes.filter((write) => write.path.endsWith("/search"))).toHaveLength(1);
  await page.getByRole("button", { name: "Xóa lần tra này" }).click();
  await expect(page.getByText("Chưa có lịch sử tra cứu.")).toBeVisible();
});

test("resolved incident links a new empty draft to its source, unresolved source is blocked", async ({
  page,
}) => {
  const state = await fixture(page);
  let resolved = true;
  await page.route(`**/api/backend/workspaces/*/incidents/${incidentId}`, (route) =>
    route.fulfill({ json: { ...incident, status: resolved ? "RESOLVED" : "OPEN" } }),
  );
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("link", { name: "Ghi vào kho kiến thức" }).click();
  await expect(page.getByLabel("Nguyên nhân đã xác nhận", { exact: false })).toHaveValue("");
  await page.getByLabel("Tiêu đề", { exact: true }).fill("Cách sửa đã kiểm tra");
  await page.getByLabel("Nguyên nhân đã xác nhận", { exact: false }).fill("Nguyên nhân thực tế");
  await page
    .getByLabel("Cách khắc phục đã kiểm tra", { exact: false })
    .fill("Các bước đã thực hiện");
  await page.getByRole("button", { name: "Lưu bản nháp" }).click();
  await expect.poll(() => state.writes.length).toBe(1);
  expect(state.writes[0].body.sourceIncidentId).toBe(incidentId);
  resolved = false;
  await page.goto(`/knowledge/new?incidentId=${incidentId}`);
  await expect(page.getByRole("main").getByRole("alert")).toContainText("Đã giải quyết");
  await expect(page.getByRole("button", { name: "Lưu bản nháp" })).toHaveCount(0);
});

test("knowledge sources preserve the quote, link revision and warn when current article changed", async ({
  page,
}) => {
  await mockOperations(page);
  const sources: AnalysisEvidence[] = [
    {
      id: "evidence",
      sourceType: "KNOWLEDGE",
      logSnippetId: null,
      logChunkIndex: null,
      knowledgeChunkId: "chunk",
      articleId,
      articleVersion: 1,
      quote: "Nguồn lịch sử đã duyệt",
      sourceSha256: "a".repeat(64),
      similarityScore: 0.82,
    },
  ];
  await page.route("**/evidence", (route) => route.fulfill({ json: sources }));
  await page.route(`**/knowledge-articles/${articleId}`, (route) =>
    route.fulfill({ json: article }),
  );
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await expect(page.getByText(sources[0].quote, { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Xem trong log đã che" })).toHaveCount(0);
  await page.getByRole("link", { name: "Mở bài viết nguồn" }).click();
  await expect(page.getByText(/Nguồn trích dẫn thuộc bản nội dung 1/)).toBeVisible();
});

test("source failure leaves the report readable without claiming verified log evidence", async ({
  page,
}) => {
  await mockOperations(page);
  await page.route("**/evidence", (route) => route.fulfill({ status: 502, json: {} }));
  await page.goto(`/incidents/${incidentId}`);
  await page.getByRole("tab", { name: "Phân tích AI", exact: true }).click();
  await expect(
    page.getByRole("main").getByRole("alert").filter({ hasText: "Chưa tải được nguồn" }),
  ).toBeVisible();
  await expect(page.getByText(analysis.summary!, { exact: true })).toBeVisible();
  await expect(page.getByText(/chưa có liên kết nguồn được xác nhận/)).toBeVisible();
});

test("BFF knowledge allowlist rejects other methods and cross-origin changes", async ({
  request,
}) => {
  const base = `/api/backend/workspaces/${workspace.id}/knowledge-articles`;
  const origin = process.env.FRONTEND_URL ?? "http://127.0.0.1:3000";
  for (const endpoint of [base, `${base}/${articleId}`])
    expect((await request.get(endpoint)).status()).toBe(401);
  for (const endpoint of [
    base,
    `${base}/search`,
    `${base}/${articleId}/approve`,
    `${base}/${articleId}/archive`,
  ]) {
    expect((await request.post(endpoint, { headers: { Origin: origin }, data: {} })).status()).toBe(
      401,
    );
    expect(
      (
        await request.post(endpoint, { headers: { Origin: "https://foreign.example" }, data: {} })
      ).status(),
    ).toBe(403);
  }
  expect(
    (
      await request.patch(`${base}/${articleId}`, { headers: { Origin: origin }, data: {} })
    ).status(),
  ).toBe(401);
  expect(
    (await request.delete(`${base}/${articleId}`, { headers: { Origin: origin } })).status(),
  ).toBe(404);
  expect((await request.get(`${base}/search`)).status()).toBe(404);
});

test("list retries errors and pagination requests only the selected page", async ({ page }) => {
  await fixture(page);
  let failed = true;
  const pages: string[] = [];
  await page.route("**/knowledge-articles?**", (route) => {
    const selectedPage = new URL(route.request().url()).searchParams.get("page") ?? "0";
    pages.push(selectedPage);
    return failed
      ? route.fulfill({ status: 502, json: {} })
      : route.fulfill({
          json: {
            items: [
              { ...article, title: selectedPage === "0" ? article.title : "Bài ở trang hai" },
            ],
            page: Number(selectedPage),
            size: 20,
            total: 21,
          },
        });
  });
  await page.goto("/knowledge");
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Không kết nối được dịch vụ",
  );
  failed = false;
  await page.getByRole("button", { name: "Thử lại", exact: true }).click();
  await expect(page.getByRole("link", { name: article.title, exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Trang sau", exact: true }).click();
  await expect(page.getByRole("link", { name: "Bài ở trang hai" })).toBeVisible();
  expect(pages.filter((value) => value === "0").length).toBeGreaterThanOrEqual(2);
  expect(pages.at(-1)).toBe("1");
  expect(new Set(pages)).toEqual(new Set(["0", "1"]));
  await expect(page.getByRole("button", { name: "Trang sau", exact: true })).toBeDisabled();
});

test("disabled RAG search explains failure while keeping the library usable", async ({ page }) => {
  await fixture(page);
  await page.route("**/knowledge-articles/search", (route) =>
    route.fulfill({ status: 503, json: { detail: "RAG integration is not configured." } }),
  );
  await page.goto("/knowledge");
  await page.getByLabel("Mô tả lỗi").fill("Không mở được kết nối");
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Tìm trong kho kiến thức" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Tìm kiếm AI chưa được bật",
  );
  await expect(page.getByRole("link", { name: article.title, exact: false })).toBeVisible();
  await expect(page.getByRole("link", { name: "Viết bài mới", exact: true })).toBeVisible();
});

for (const [width, height] of [
  [1440, 900],
  [390, 844],
  [320, 812],
]) {
  test(`knowledge is accessible and fits at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await fixture(page, { delay: true });
    await page.goto("/knowledge");
    await expect(
      page.getByRole("status").filter({ hasText: "Đang tải kho kiến thức" }),
    ).toBeVisible();
    await expect(page.getByRole("link", { name: article.title, exact: false })).toBeVisible();
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    const dir = path.resolve(process.cwd(), "../.verification/knowledge-ui");
    await mkdir(dir, { recursive: true });
    await page.screenshot({ path: path.join(dir, `knowledge-${width}.png`), fullPage: true });
    await page.getByRole("link", { name: article.title, exact: false }).click();
    await page.getByRole("button", { name: "Chỉnh sửa bài" }).click();
    await page
      .getByLabel("Tiêu đề", { exact: true })
      .fill("Tiêu đề dài để kiểm tra bố cục và dòng chữ không gây tràn khung trên thiết bị nhỏ");
    await page
      .getByLabel("Cách khắc phục đã kiểm tra", { exact: false })
      .fill("chuỗi-dài-không-ngắt".repeat(30));
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: path.join(dir, `editor-${width}.png`), fullPage: true });
    await page.getByRole("button", { name: "Hủy chỉnh sửa" }).click();
    await page.getByRole("button", { name: "Duyệt bài viết", exact: true }).click();
    await expect(page.getByRole("button", { name: "Đóng", exact: true })).toBeFocused();
    await page.keyboard.press("Shift+Tab");
    await expect(page.getByRole("button", { name: "Xác nhận duyệt" })).toBeFocused();
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", { name: "Duyệt bài viết", exact: true })).toBeFocused();
  });
}
