import { expect, test, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";

const origin = process.env.FRONTEND_URL ?? "http://127.0.0.1:3000";
const headers = { Origin: origin };
const password = `Local-${randomUUID()}`;

test.beforeEach(() => {
  test.skip(
    process.env.TRACEPILOT_LIVE !== "1",
    "Set TRACEPILOT_LIVE=1 and start the Spring backend to run live integration tests.",
  );
});

async function registerAndLogin(request: APIRequestContext) {
  const email = `frontend-test-${randomUUID()}@example.test`;
  const registered = await request.post("/api/auth/register", {
    headers,
    data: { email, password, displayName: "Frontend Test" },
  });
  expect(registered.status()).toBe(201);
  const user = await registered.json();
  const login = await request.post("/api/auth/login", { headers, data: { email, password } });
  expect(login.status()).toBe(200);
  expect(await login.json()).toEqual(user);
  return { user, email };
}

test("live registration, login, reload and logout through the UI", async ({ page }) => {
  const email = `frontend-ui-${randomUUID()}@example.test`;
  await page.goto("/register");
  await page.getByLabel("Tên hiển thị").fill("Frontend UI Test");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Tạo tài khoản", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Tài khoản đã được tạo");
  await expect(page.getByLabel("Email", { exact: true })).toHaveValue(email);
  await expect(page.getByLabel("Mật khẩu", { exact: true })).toHaveValue("");
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page).toHaveURL(origin + "/");
  await expect(page.getByRole("heading", { name: "Không gian cho dự án tiếp theo" })).toBeVisible();
  await page.getByRole("button", { name: "Tạo workspace", exact: true }).last().click();
  await page.getByLabel("Tên workspace").fill("Live UI Workspace");
  await page.getByLabel("Slug", { exact: true }).fill(`ui-${randomUUID()}`);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Tạo workspace", exact: true })
    .click();
  await expect(page.getByRole("heading", { name: "Live UI Workspace", exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "Live UI Workspace", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Đăng xuất", exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(
    (await page.context().cookies()).filter((cookie) => cookie.name.startsWith("tp_")),
  ).toHaveLength(0);
});

test("live auth hides tokens, refreshes concurrent requests and revokes logout", async ({
  request,
}) => {
  const { user } = await registerAndLogin(request);
  const state = await request.storageState();
  const access = state.cookies.find((cookie) => cookie.name === "tp_access");
  const refresh = state.cookies.find((cookie) => cookie.name === "tp_refresh");
  expect(access?.httpOnly).toBe(true);
  expect(refresh?.httpOnly).toBe(true);
  expect(access?.path).toBe("/api");
  expect(refresh?.path).toBe("/api/auth");
  expect((await request.get("/api/auth/me")).status()).toBe(200);
  const results = await Promise.all(
    [0, 1].map(() =>
      request.post("/api/auth/refresh", {
        headers: { ...headers, Cookie: `tp_refresh=${refresh?.value}` },
      }),
    ),
  );
  for (const response of results) {
    expect(response.status()).toBe(200);
    expect(await response.json()).toEqual(user);
  }
  const rotated = (await request.storageState()).cookies.find(
    (cookie) => cookie.name === "tp_refresh",
  );
  expect(rotated?.value === refresh?.value).toBe(false);
  expect((await request.post("/api/auth/logout", { headers })).status()).toBe(204);
  expect((await request.get("/api/auth/me")).status()).toBe(401);
  const revoked = await request.post("/api/auth/refresh", {
    headers: { ...headers, Cookie: `tp_refresh=${rotated?.value}` },
  });
  expect(revoked.status()).toBe(401);
});

test("live client restores an expired access cookie using refresh", async ({ page }) => {
  await registerAndLogin(page.request);
  await page.context().clearCookies({ name: "tp_access" });
  await page.goto("/account");
  await expect(page.getByRole("heading", { name: "Tài khoản", exact: true })).toBeVisible();
  expect((await page.context().cookies()).some((cookie) => cookie.name === "tp_access")).toBe(true);
  expect(await page.evaluate(() => localStorage.getItem("accessToken"))).toBeNull();
});

test("live workspace isolation and membership permissions survive the proxy", async ({
  request,
  playwright,
}) => {
  const owner = await registerAndLogin(request);
  const strangerRequest = await playwright.request.newContext({ baseURL: origin });
  try {
    const stranger = await registerAndLogin(strangerRequest);
    const created = await request.post("/api/backend/workspaces", {
      headers,
      data: { name: "RBAC test", slug: `rbac-${randomUUID()}` },
    });
    expect(created.status()).toBe(201);
    const workspace = await created.json();
    const path = `/api/backend/workspaces/${workspace.id}`;
    expect((await strangerRequest.get(path)).status()).toBe(404);
    expect(
      (
        await request.post(`${path}/members`, {
          headers,
          data: { userId: stranger.user.id, role: "MEMBER" },
        })
      ).status(),
    ).toBe(201);
    expect((await strangerRequest.get(path)).status()).toBe(200);
    expect((await strangerRequest.post(`${path}/github/sync`, { headers })).status()).toBe(403);
    expect(
      (
        await strangerRequest.patch(`${path}/members/${stranger.user.id}`, {
          headers,
          data: { role: "ADMIN" },
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await request.patch(`${path}/members/${owner.user.id}`, {
          headers,
          data: { role: "MEMBER" },
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await request.patch(`${path}/members/${stranger.user.id}`, {
          headers,
          data: { role: "ADMIN" },
        })
      ).status(),
    ).toBe(200);
    expect(
      (await request.delete(`${path}/members/${stranger.user.id}`, { headers })).status(),
    ).toBe(204);
    expect((await strangerRequest.get(path)).status()).toBe(404);
    const repos = await request.get(`${path}/repositories?page=0&size=20`);
    expect(repos.status()).toBe(200);
    expect((await repos.json()).items).toEqual([]);
  } finally {
    await strangerRequest.dispose();
  }
});

test("live proxy rejects cross-site writes, unknown routes and malformed input", async ({
  request,
}) => {
  expect(
    (
      await request.post("/api/auth/login", {
        headers: { Origin: "https://attacker.example" },
        data: {},
      })
    ).status(),
  ).toBe(403);
  expect((await request.post("/api/auth/login", { data: {} })).status()).toBe(403);
  expect((await request.get("/api/backend/actuator/health")).status()).toBe(404);
  expect(
    (
      await request.post("/api/auth/login", {
        headers: { ...headers, "Content-Type": "application/json" },
        data: "{",
      })
    ).status(),
  ).toBe(400);
  expect(
    (
      await request.post("/api/auth/login", { headers, data: { email: "x".repeat(9000) } })
    ).status(),
  ).toBe(413);
  expect(
    (
      await request.post("/api/auth/login", {
        headers: { ...headers, "Content-Type": "text/plain" },
        data: "test",
      })
    ).status(),
  ).toBe(415);
  expect(
    (
      await request.post("/api/auth/register", {
        headers,
        data: { email: "invalid", password: "short", displayName: "" },
      })
    ).status(),
  ).toBe(400);
  const { email } = await registerAndLogin(request);
  expect(
    (
      await request.post("/api/auth/register", {
        headers,
        data: { email, password, displayName: "Duplicate" },
      })
    ).status(),
  ).toBe(409);
});
