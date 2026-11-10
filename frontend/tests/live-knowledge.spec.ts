import { expect, test, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";

const origin = process.env.FRONTEND_URL ?? "http://127.0.0.1:3000";
const headers = { Origin: origin };

test.beforeEach(() =>
  test.skip(
    process.env.TRACEPILOT_LIVE !== "1",
    "Requires the live Spring backend with AI/RAG disabled.",
  ),
);

async function login(request: APIRequestContext) {
  const email = `frontend-knowledge-${randomUUID()}@example.test`;
  const password = `Local-${randomUUID()}`;
  expect(
    (
      await request.post("/api/auth/register", {
        headers,
        data: { email, password, displayName: "Knowledge Test" },
      })
    ).status(),
  ).toBe(201);
  const response = await request.post("/api/auth/login", { headers, data: { email, password } });
  expect(response.status()).toBe(200);
  return response.json();
}

test("live knowledge UI persists long Vietnamese text, versions, roles and tenant isolation through Next", async ({
  page,
  playwright,
}) => {
  await login(page.request);
  const created = await page.request.post("/api/backend/workspaces", {
    headers,
    data: { name: "Knowledge UI test", slug: `knowledge-${randomUUID()}` },
  });
  expect(created.status()).toBe(201);
  const workspace = await created.json();
  const base = `/api/backend/workspaces/${workspace.id}/knowledge-articles`;
  await page.goto("/knowledge/new");
  const rootCause = "Lỗi kết nối đã xác nhận. ".repeat(90);
  const resolution = "Kiểm tra cấu hình rồi chạy lại dịch vụ. ".repeat(130);
  expect(Buffer.byteLength(JSON.stringify({ rootCause, resolution }), "utf8")).toBeGreaterThan(
    8192,
  );
  await page.getByLabel("Tiêu đề", { exact: true }).fill("Bài kiểm thử giao diện và lưu trữ");
  await page
    .getByLabel("Triệu chứng và dấu hiệu lỗi", { exact: false })
    .fill("Kết nối bị từ chối trong kiểm thử");
  await page.getByLabel("Phạm vi áp dụng", { exact: true }).fill("Local fixture, Java 21");
  await page
    .getByLabel("Cách kiểm chứng và kết quả", { exact: false })
    .fill("Đã chạy lại kiểm thử trên môi trường tạm");
  await page.getByLabel("Nguyên nhân đã xác nhận", { exact: false }).fill(rootCause);
  await page.getByLabel("Cách khắc phục đã kiểm tra", { exact: false }).fill(resolution);
  await page.getByRole("button", { name: "Lưu bản nháp" }).click();
  await expect(page).toHaveURL(/\/knowledge\/[0-9a-f-]{36}$/);
  const articleId = new URL(page.url()).pathname.split("/").at(-1);
  const storedResponse = await page.request.get(`${base}/${articleId}`);
  expect(storedResponse.status()).toBe(200);
  const article = await storedResponse.json();
  expect(article).toMatchObject({
    status: "DRAFT",
    indexStatus: "NONE",
    rootCause: rootCause.trim(),
    resolution: resolution.trim(),
  });
  await page.getByRole("button", { name: "Chỉnh sửa bài" }).click();
  await page.getByLabel("Tiêu đề", { exact: true }).fill("Bài đã sửa từ giao diện");
  await page.getByRole("button", { name: "Lưu bản nháp" }).click();
  await expect(page.getByRole("heading", { name: "Bài đã sửa từ giao diện" })).toBeVisible();
  const updated = await (await page.request.get(`${base}/${articleId}`)).json();
  expect(updated.version).toBeGreaterThan(article.version);
  expect(
    (
      await page.request.patch(`${base}/${articleId}`, {
        headers,
        data: { version: article.version, title: "Stale", rootCause: "Stale", resolution: "Stale" },
      })
    ).status(),
  ).toBe(409);
  await page.getByRole("button", { name: "Duyệt bài viết", exact: true }).click();
  await page.getByRole("checkbox", { name: /Tôi đã kiểm tra nguyên nhân/ }).check();
  await page.getByRole("button", { name: "Xác nhận duyệt" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Tìm kiếm AI chưa được bật",
  );
  await page.getByRole("button", { name: "Tải lại bài viết" }).click();
  expect(
    (
      await page.request.post(`${base}/search`, {
        headers,
        data: { query: "Kết nối thất bại", topK: 3, mode: "HYBRID" },
      })
    ).status(),
  ).toBe(503);
  const member = await playwright.request.newContext({ baseURL: origin });
  const outsider = await playwright.request.newContext({ baseURL: origin });
  try {
    const memberUser = await login(member);
    await login(outsider);
    expect(
      (
        await page.request.post(`/api/backend/workspaces/${workspace.id}/members`, {
          headers,
          data: { userId: memberUser.id, role: "MEMBER" },
        })
      ).status(),
    ).toBe(201);
    expect((await member.get(`${base}/${articleId}`)).status()).toBe(200);
    expect(
      (
        await member.patch(`${base}/${articleId}`, {
          headers,
          data: {
            version: updated.version,
            title: "Foreign edit",
            rootCause: "Cause",
            resolution: "Fix",
          },
        })
      ).status(),
    ).toBe(403);
    for (const action of ["approve", "archive"])
      expect(
        (
          await member.post(`${base}/${articleId}/${action}`, {
            headers,
            data: { version: updated.version },
          })
        ).status(),
      ).toBe(403);
    expect((await outsider.get(base)).status()).toBe(404);
    expect((await outsider.get(`${base}/${articleId}`)).status()).toBe(404);
    expect(
      (
        await outsider.post(base, {
          headers,
          data: { title: "Foreign", rootCause: "Cause", resolution: "Fix" },
        })
      ).status(),
    ).toBe(404);
    const memberDraft = await member.post(base, {
      headers,
      data: { title: "Member draft", rootCause: "Cause", resolution: "Verified fix" },
    });
    expect(memberDraft.status()).toBe(201);
    const own = await memberDraft.json();
    expect(
      (
        await member.patch(`${base}/${own.id}`, {
          headers,
          data: {
            version: own.version,
            title: "Member edit",
            rootCause: "Cause",
            resolution: "Verified fix",
          },
        })
      ).status(),
    ).toBe(200);
    const archived = await page.request.post(`${base}/${articleId}/archive`, {
      headers,
      data: { version: updated.version },
    });
    expect(archived.status()).toBe(200);
    expect(await archived.json()).toMatchObject({ status: "ARCHIVED" });
  } finally {
    await member.dispose();
    await outsider.dispose();
  }
});
