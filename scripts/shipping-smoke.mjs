import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { parseArgs, parseEnv } from "node:util";

const { values } = parseArgs({
  options: {
    "base-url": { type: "string" },
    "backend-url": { type: "string" },
    samples: { type: "string", default: "25" },
    output: { type: "string", default: ".verification/phase09/smoke.json" },
  },
});
const config = parseEnv(await readFile(".env.shipping", "utf8"));
const base = new URL(values["base-url"] ?? config.APP_ORIGIN);
const backend = new URL(
  values["backend-url"] ?? `http://127.0.0.1:${config.BACKEND_PORT ?? 18081}`,
);
const samples = Number(values.samples);
assert(
  Number.isInteger(samples) && samples >= 5 && samples <= 100,
  "Use 5-100 sequential samples.",
);
for (const url of [base, backend]) {
  assert(
    ["127.0.0.1", "localhost", "[::1]"].includes(url.hostname),
    "Smoke writes require a loopback target.",
  );
  assert(
    !url.username && !url.password,
    "Do not put credentials in target URLs.",
  );
}
const requestId =
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

function session() {
  const cookies = new Map();
  return {
    cookies,
    async call(route, { method = "GET", body, origin = base.origin } = {}) {
      const headers = { "Content-Type": "application/json" };
      if (method !== "GET") headers.Origin = origin;
      const selected = [...cookies.values()].filter((cookie) =>
        route.startsWith(cookie.path),
      );
      if (selected.length)
        headers.Cookie = selected
          .map((cookie) => `${cookie.name}=${cookie.value}`)
          .join("; ");
      const response = await fetch(new URL(route, base), {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        redirect: "error",
        signal: AbortSignal.timeout(15000),
      });
      for (const raw of response.headers.getSetCookie()) {
        const [pair, ...attributes] = raw.split(";").map((item) => item.trim());
        const separator = pair.indexOf("=");
        const name = pair.slice(0, separator);
        const value = pair.slice(separator + 1);
        const cookiePath =
          attributes
            .find((item) => item.toLowerCase().startsWith("path="))
            ?.slice(5) ?? "/";
        if (!value) cookies.delete(name);
        else cookies.set(name, { name, value, path: cookiePath, attributes });
      }
      return response;
    },
  };
}

async function expectStatus(response, status) {
  assert.equal(
    response.status,
    status,
    `Expected HTTP ${status}, received ${response.status}.`,
  );
  return response;
}

async function account(client) {
  const email = `shipping-${randomUUID()}@example.test`;
  const password = `Shipping-${randomUUID()}`;
  await expectStatus(
    await client.call("/api/auth/register", {
      method: "POST",
      body: { email, password, displayName: "Shipping smoke" },
    }),
    201,
  );
  const login = await expectStatus(
    await client.call("/api/auth/login", {
      method: "POST",
      body: { email, password },
    }),
    200,
  );
  const user = await login.json();
  assert.equal(
    user.accessToken,
    undefined,
    "Tokens must remain in HttpOnly cookies.",
  );
  for (const name of ["tp_access", "tp_refresh"]) {
    const cookie = client.cookies.get(name);
    assert(cookie, `Missing ${name}.`);
    const flags = cookie.attributes.map((attribute) => attribute.toLowerCase());
    assert(
      flags.includes("httponly") &&
        flags.includes("secure") &&
        flags.includes("samesite=lax"),
    );
    assert.equal(cookie.path, name === "tp_access" ? "/api" : "/api/auth");
  }
  return user;
}

async function measure(name, call) {
  for (let index = 0; index < 3; index++) await expectStatus(await call(), 200);
  const durations = [];
  for (let index = 0; index < samples; index++) {
    const start = performance.now();
    const response = await expectStatus(await call(), 200);
    await response.arrayBuffer();
    durations.push(performance.now() - start);
  }
  durations.sort((left, right) => left - right);
  const percentile = (fraction) =>
    Number(durations[Math.ceil(durations.length * fraction) - 1].toFixed(2));
  return {
    name,
    samples,
    concurrency: 1,
    p50Ms: percentile(0.5),
    p95Ms: percentile(0.95),
    maxMs: percentile(1),
  };
}

async function main() {
  const readiness = () =>
    fetch(new URL("/actuator/health/readiness", backend), {
      signal: AbortSignal.timeout(10000),
    });
  const health = await expectStatus(await readiness(), 200);
  assert.equal((await health.json()).status, "UP");
  await expectStatus(await fetch(new URL("/login", base)), 200);
  const owner = session();
  const outsider = session();
  const member = await account(outsider);
  await account(owner);
  const created = await expectStatus(
    await owner.call("/api/backend/workspaces", {
      method: "POST",
      body: { name: "Shipping smoke", slug: `shipping-${randomUUID()}` },
    }),
    201,
  );
  const trace = created.headers.get("x-request-id");
  assert.match(trace, requestId);
  const workspace = await created.json();
  const scope = `/api/backend/workspaces/${workspace.id}`;
  await expectStatus(await outsider.call(scope), 404);
  const audit = await expectStatus(
    await owner.call(`${scope}/audit-logs`),
    200,
  );
  assert(
    (await audit.json()).items.some(
      (entry) =>
        entry.action === "WORKSPACE_CREATED" && entry.requestId === trace,
    ),
  );
  await expectStatus(
    await owner.call(`${scope}/members`, {
      method: "POST",
      body: { userId: member.id, role: "MEMBER" },
    }),
    201,
  );
  await expectStatus(await outsider.call(`${scope}/operations`), 403);
  await expectStatus(
    await owner.call("/api/backend/workspaces", {
      method: "POST",
      origin: "https://foreign.example",
      body: { name: "Rejected", slug: "rejected" },
    }),
    403,
  );
  await expectStatus(await owner.call("/api/backend/actuator/metrics"), 404);
  const unknown = `rate-${randomUUID()}@example.test`;
  for (let index = 0; index < 10; index++) {
    await expectStatus(
      await owner.call("/api/auth/login", {
        method: "POST",
        body: { email: unknown, password: "Wrong-password!" },
      }),
      401,
    );
  }
  const throttled = await expectStatus(
    await owner.call("/api/auth/login", {
      method: "POST",
      body: { email: unknown, password: "Wrong-password!" },
    }),
    429,
  );
  assert(Number(throttled.headers.get("retry-after")) > 0);
  assert.match(throttled.headers.get("x-request-id"), requestId);
  const metrics = [
    await measure("backend readiness", readiness),
    await measure("Next BFF auth/me", () => owner.call("/api/auth/me")),
    await measure("Next BFF workspace operations", () =>
      owner.call(`${scope}/operations`),
    ),
  ];
  await expectStatus(
    await owner.call("/api/auth/logout", { method: "POST" }),
    204,
  );
  await expectStatus(await owner.call("/api/auth/me"), 401);
  await expectStatus(
    await outsider.call("/api/auth/logout", { method: "POST" }),
    204,
  );
  const report = {
    checkedAt: new Date().toISOString(),
    scope:
      "Local container smoke; sequential requests; no provider calls or capacity claim",
    frontendOrigin: base.origin,
    checks: [
      "readiness",
      "login and secure cookies",
      "audit correlation",
      "workspace isolation",
      "manager RBAC",
      "origin rejection",
      "proxy allowlist",
      "rate-limit headers",
      "logout revocation",
    ],
    metrics,
  };
  await mkdir(path.dirname(values.output), { recursive: true });
  await writeFile(values.output, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));
}

main().catch((failure) => {
  // Never print response bodies, credentials or cookie values on failed checks.
  console.error(`Shipping smoke failed: ${failure.message}`);
  process.exitCode = 1;
});
