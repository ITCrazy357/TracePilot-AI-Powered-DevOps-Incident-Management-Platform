import { NextRequest, NextResponse } from "next/server";
import {
  ACCESS_COOKIE,
  backend,
  checkOrigin,
  forward,
  problem,
  readJson,
} from "@/lib/server/backend";

const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const workspace = `workspaces/${uuid}`;
const runs = `${workspace}/repositories/${uuid}/workflow-runs`;
const incidents = `${workspace}/incidents`;
const analyses = `${incidents}/${uuid}/analyses`;
const knowledge = `${workspace}/knowledge-articles`;
const routes: Record<string, RegExp[]> = {
  GET: [
    /^workspaces$/,
    new RegExp(`^workspaces/${uuid}$`),
    new RegExp(`^workspaces/${uuid}/(members|repositories)$`),
    new RegExp(`^${runs}(?:/sync-requests|/${uuid}(?:/jobs)?)?$`),
    new RegExp(`^${incidents}(?:/${uuid}(?:/logs)?)?$`),
    new RegExp(`^${analyses}(?:/${uuid}(?:/usage|/feedback|/evidence)?)?$`),
    new RegExp(`^${knowledge}(?:/${uuid})?$`),
    new RegExp(`^${knowledge}/search-history(?:/${uuid})?$`),
    new RegExp(`^${workspace}/quality$`),
    new RegExp(`^${workspace}/(?:audit-logs|operations(?:/outbox)?)$`),
    new RegExp(`^${workspace}/notifications(?:/stream)?$`),
  ],
  POST: [
    /^workspaces$/,
    new RegExp(`^${workspace}/(members|github/sync)$`),
    new RegExp(`^${runs}/sync$`),
    new RegExp(`^${analyses}$`),
    new RegExp(`^${knowledge}(?:/search|/${uuid}/(?:approve|archive))?$`),
    new RegExp(`^${workspace}/operations/outbox/${uuid}/retry$`),
  ],
  PATCH: [
    new RegExp(`^${knowledge}/${uuid}$`),
    new RegExp(`^${workspace}/members/${uuid}$`),
    new RegExp(`^${incidents}/${uuid}/(status|assignee)$`),
    new RegExp(`^${workspace}/notifications/${uuid}/read$`),
  ],
  PUT: [new RegExp(`^${analyses}/${uuid}/feedback$`)],
  DELETE: [
    new RegExp(`^workspaces/${uuid}/members/${uuid}$`),
    new RegExp(`^${knowledge}/search-history/${uuid}$`),
  ],
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
  if (path.endsWith("/notifications/stream")) {
    const lastEventId =
      request.headers.get("last-event-id") ?? request.nextUrl.searchParams.get("lastEventId");
    if (lastEventId && !new RegExp(`^${uuid}$`).test(lastEventId))
      return problem(400, "Invalid event ID.");
    const response = await backend(path, {
      signal: request.signal,
      headers: {
        Authorization: `Bearer ${access}`,
        Accept: "text/event-stream",
        ...(lastEventId ? { "Last-Event-ID": lastEventId } : {}),
      },
    });
    if (!response.ok) return forward(response);
    return new NextResponse(response.body, {
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-store, no-transform",
        "X-Accel-Buffering": "no",
      },
    });
  }
  let body: string | undefined;
  if (
    ["POST", "PATCH", "PUT"].includes(request.method) &&
    !path.endsWith("github/sync") &&
    !path.endsWith("/read")
  ) {
    const payload = await readJson(request, path.includes("/knowledge-articles") ? 65_536 : 8192);
    if (payload.error) return payload.error;
    body = JSON.stringify(payload.body);
  }
  const query = new URLSearchParams();
  if (request.method === "GET") {
    for (const key of path.endsWith("/quality") ? ["days"] : ["page", "size"]) {
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
export const PUT = handle;
export const DELETE = handle;
