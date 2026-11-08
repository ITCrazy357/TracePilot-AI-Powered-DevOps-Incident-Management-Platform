import { expect, test, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";

const origin = process.env.FRONTEND_URL ?? "http://127.0.0.1:3000";
const headers = { Origin: origin };

test.beforeEach(() =>
  test.skip(process.env.TRACEPILOT_LIVE !== "1", "Requires the live Spring backend."),
);

async function login(request: APIRequestContext) {
  const email = `frontend-operations-${randomUUID()}@example.test`;
  const password = `Local-${randomUUID()}`;
  expect(
    (
      await request.post("/api/auth/register", {
        headers,
        data: { email, password, displayName: "Operations Test" },
      })
    ).status(),
  ).toBe(201);
  expect(
    (await request.post("/api/auth/login", { headers, data: { email, password } })).status(),
  ).toBe(200);
}

test("live operations API and SSE retain workspace isolation through Next", async ({
  page,
  playwright,
}) => {
  await login(page.request);
  const created = await page.request.post("/api/backend/workspaces", {
    headers,
    data: { name: "Operations proxy test", slug: `operations-${randomUUID()}` },
  });
  expect(created.status()).toBe(201);
  const workspace = await created.json();
  const base = `/api/backend/workspaces/${workspace.id}`;
  for (const suffix of ["incidents", "notifications"]) {
    const response = await page.request.get(`${base}/${suffix}?page=0&size=20`);
    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({ items: [], page: 0, size: 20, total: 0 });
  }
  const missingId = randomUUID();
  for (const suffix of [
    `incidents/${missingId}/logs`,
    `incidents/${missingId}/analyses`,
    `repositories/${missingId}/workflow-runs`,
  ]) {
    const response = await page.request.get(`${base}/${suffix}`);
    expect(response.status()).toBe(404);
    expect((await response.json()).detail).not.toBe("Unknown endpoint.");
  }
  await page.goto("/notifications");
  await expect(page.getByRole("heading", { name: "Hộp thông báo" })).toBeVisible();
  const stream = await page.evaluate(async (url) => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10_000);
    try {
      const response = await fetch(url, { signal: controller.signal });
      const chunk = await response.body?.getReader().read();
      return {
        status: response.status,
        type: response.headers.get("content-type"),
        cache: response.headers.get("cache-control"),
        received: Boolean(chunk?.value?.length),
      };
    } finally {
      clearTimeout(timeout);
      controller.abort();
    }
  }, `${base}/notifications/stream`);
  expect(stream).toEqual({
    status: 200,
    type: "text/event-stream",
    cache: "no-store, no-transform",
    received: true,
  });
  const outsider = await playwright.request.newContext({ baseURL: origin });
  try {
    await login(outsider);
    expect((await outsider.get(`${base}/incidents`)).status()).toBe(404);
    expect((await outsider.get(`${base}/notifications/stream`)).status()).toBe(404);
  } finally {
    await outsider.dispose();
  }
});
