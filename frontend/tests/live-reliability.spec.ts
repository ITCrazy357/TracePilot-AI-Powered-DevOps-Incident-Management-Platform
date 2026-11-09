import { expect, test, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";

const origin = process.env.FRONTEND_URL ?? "http://127.0.0.1:3000";
const headers = { Origin: origin };
const requestId = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

test.beforeEach(() =>
  test.skip(process.env.TRACEPILOT_LIVE !== "1", "Requires the current Spring backend."),
);

async function login(request: APIRequestContext) {
  const email = `frontend-reliability-${randomUUID()}@example.test`;
  const password = `Local-${randomUUID()}`;
  expect(
    (
      await request.post("/api/auth/register", {
        headers,
        data: { email, password, displayName: "Reliability Test" },
      })
    ).status(),
  ).toBe(201);
  const response = await request.post("/api/auth/login", { headers, data: { email, password } });
  expect(response.status()).toBe(200);
  return response.json();
}

test("live audit matches request correlation and cannot be read by members", async ({
  page,
  playwright,
}) => {
  await login(page.request);
  const created = await page.request.post("/api/backend/workspaces", {
    headers,
    data: { name: "Reliability workspace", slug: `reliability-${randomUUID()}` },
  });
  expect(created.status()).toBe(201);
  const trace = created.headers()["x-request-id"];
  expect(trace).toMatch(requestId);
  const workspace = await created.json();
  const base = `/api/backend/workspaces/${workspace.id}`;
  const audit = await page.request.get(`${base}/audit-logs?size=20`);
  expect(audit.status()).toBe(200);
  expect((await audit.json()).items).toEqual([
    expect.objectContaining({
      action: "WORKSPACE_CREATED",
      entityId: workspace.id,
      metadata: {},
      requestId: trace,
    }),
  ]);
  const member = await playwright.request.newContext({ baseURL: origin });
  try {
    const user = await login(member);
    expect((await member.get(`${base}/audit-logs`)).status()).toBe(404);
    expect(
      (
        await page.request.post(`${base}/members`, {
          headers,
          data: { userId: user.id, role: "MEMBER" },
        })
      ).status(),
    ).toBe(201);
    expect((await member.get(`${base}/audit-logs`)).status()).toBe(403);
    expect((await member.get(`${base}/operations`)).status()).toBe(403);
    expect((await member.get(`${base}/operations/outbox`)).status()).toBe(403);
  } finally {
    await member.dispose();
  }
  await page.goto("/operations");
  await expect(page.getByRole("heading", { name: "Chưa có tác vụ", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Audit", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Tạo workspace", exact: true })).toBeVisible();
  await expect(page.getByText(trace, { exact: true })).toBeVisible();
});

test("live BFF preserves retry-after and request ID for a throttled account", async ({
  request,
}) => {
  const email = `rate-fixture-${randomUUID()}@example.test`;
  for (let index = 0; index < 10; index++) {
    expect(
      (
        await request.post("/api/auth/login", {
          headers,
          data: { email, password: "Wrong-password!" },
        })
      ).status(),
    ).toBe(401);
  }
  const rejected = await request.post("/api/auth/login", {
    headers,
    data: { email, password: "Wrong-password!" },
  });
  expect(rejected.status()).toBe(429);
  expect(Number(rejected.headers()["retry-after"])).toBeGreaterThan(0);
  expect(rejected.headers()["x-request-id"]).toMatch(requestId);
  expect(rejected.headers()["cache-control"]).toBe("no-store");
  expect(await rejected.text()).not.toContain(email);
});

test("live scoped retry reaches Spring without opening an unscoped proxy", async ({ request }) => {
  await login(request);
  const workspace = await (
    await request.post("/api/backend/workspaces", {
      headers,
      data: { name: "Retry proxy", slug: `retry-${randomUUID()}` },
    })
  ).json();
  const response = await request.post(
    `/api/backend/workspaces/${workspace.id}/operations/outbox/${randomUUID()}/retry`,
    {
      headers,
      data: { replayCount: 0 },
    },
  );
  expect(response.status()).toBe(404);
  expect((await response.json()).detail).not.toBe("Unknown endpoint.");
  expect(response.headers()["x-request-id"]).toMatch(requestId);
  expect((await request.get("/api/backend/actuator/metrics")).status()).toBe(404);
});
