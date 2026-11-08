import { NextRequest } from "next/server";
import {
  ACCESS_COOKIE,
  backend,
  checkOrigin,
  forward,
  problem,
  readJson,
} from "@/lib/server/backend";

const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const routes: Record<string, RegExp[]> = {
  GET: [
    /^workspaces$/,
    new RegExp(`^workspaces/${uuid}$`),
    new RegExp(`^workspaces/${uuid}/(members|repositories)$`),
  ],
  POST: [/^workspaces$/, new RegExp(`^workspaces/${uuid}/(members|github/sync)$`)],
  PATCH: [new RegExp(`^workspaces/${uuid}/members/${uuid}$`)],
  DELETE: [new RegExp(`^workspaces/${uuid}/members/${uuid}$`)],
};

async function handle(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  if (request.method !== "GET") {
    const rejected = checkOrigin(request);
    if (rejected) return rejected;
  }
  const path = (await context.params).path.join("/");
  if (!routes[request.method]?.some((pattern) => pattern.test(path)))
    return problem(404, "Unknown endpoint.");
  const access = request.cookies.get(ACCESS_COOKIE)?.value;
  if (!access) return problem(401, "");
  let body: string | undefined;
  if (["POST", "PATCH"].includes(request.method) && !path.endsWith("github/sync")) {
    const payload = await readJson(request);
    if (payload.error) return payload.error;
    body = JSON.stringify(payload.body);
  }
  const query = new URLSearchParams();
  if (request.method === "GET" && path.endsWith("/repositories")) {
    for (const key of ["page", "size"]) {
      const value = request.nextUrl.searchParams.get(key);
      if (value !== null) query.set(key, value);
    }
  }
  const suffix = query.size ? `?${query}` : "";
  return forward(
    await backend(path + suffix, {
      method: request.method,
      headers: { Authorization: `Bearer ${access}` },
      body,
    }),
  );
}

export const GET = handle;
export const POST = handle;
export const PATCH = handle;
export const DELETE = handle;
