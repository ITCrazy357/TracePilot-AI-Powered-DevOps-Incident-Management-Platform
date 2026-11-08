import { NextRequest, NextResponse } from "next/server";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  backend,
  checkOrigin,
  clearSession,
  establishSession,
  forward,
  problem,
  readJson,
  refreshSession,
} from "@/lib/server/backend";

type Context = { params: Promise<{ action: string }> };

export async function GET(request: NextRequest, context: Context) {
  const { action } = await context.params;
  if (action !== "me") return problem(404, "Unknown endpoint.");
  const access = request.cookies.get(ACCESS_COOKIE)?.value;
  if (!access) return problem(401, "");
  return forward(await backend("auth/me", { headers: { Authorization: `Bearer ${access}` } }));
}

export async function POST(request: NextRequest, context: Context) {
  const rejected = checkOrigin(request);
  if (rejected) return rejected;
  const { action } = await context.params;
  if (action === "refresh") {
    const refresh = request.cookies.get(REFRESH_COOKIE)?.value;
    if (!refresh) return problem(401, "");
    const upstream = await refreshSession(refresh);
    if (upstream.status === 401) return clearSession(problem(401, ""));
    return establishSession(upstream);
  }
  if (action === "logout") {
    const refresh = request.cookies.get(REFRESH_COOKIE)?.value;
    if (refresh) {
      const upstream = await backend("auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken: refresh }),
      });
      if (!upstream.ok) return forward(upstream);
    }
    return clearSession(
      new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } }),
    );
  }
  if (action !== "login" && action !== "register") return problem(404, "Unknown endpoint.");
  const payload = await readJson(request);
  if (payload.error) return payload.error;
  const { email, password, displayName } = payload.body;
  const body = action === "register" ? { email, password, displayName } : { email, password };
  const upstream = await backend(`auth/${action}`, { method: "POST", body: JSON.stringify(body) });
  return action === "login" ? establishSession(upstream) : forward(upstream);
}
