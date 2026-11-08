import "server-only";

import { NextRequest, NextResponse } from "next/server";

export const ACCESS_COOKIE = "tp_access";
export const REFRESH_COOKIE = "tp_refresh";
const REFRESH_TTL = 7 * 24 * 60 * 60;

interface Tokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: { id: string; email: string; displayName: string };
}

export function problem(status: number, detail: string) {
  return NextResponse.json(
    { status, detail },
    {
      status,
      headers: { "Cache-Control": "no-store" },
    },
  );
}

export function checkOrigin(request: NextRequest) {
  const expectedOrigin = process.env.APP_ORIGIN ?? "http://127.0.0.1:3000";
  if (
    request.headers.get("origin") !== expectedOrigin ||
    request.headers.get("sec-fetch-site") === "cross-site"
  ) {
    return problem(403, "Request origin is not allowed.");
  }
  return null;
}

export async function backend(path: string, init: RequestInit = {}) {
  const base = process.env.BACKEND_URL ?? "http://127.0.0.1:8080";
  try {
    return await fetch(`${base}/api/v1/${path}`, {
      ...init,
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(path.endsWith("github/sync") ? 180_000 : 15_000),
      headers: { "Content-Type": "application/json", ...init.headers },
    });
  } catch {
    return new Response(
      JSON.stringify({
        status: 502,
        detail: "Không kết nối được backend. Kiểm tra dịch vụ rồi thử lại.",
      }),
      { status: 502, headers: { "Content-Type": "application/problem+json" } },
    );
  }
}

export async function forward(response: Response) {
  if (response.status === 204) {
    return new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  }
  // Forward only Problem Detail fields. Never relay arbitrary upstream HTML or headers.
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    return problem(response.status, typeof body?.detail === "string" ? body.detail : "");
  }
  return NextResponse.json(body, {
    status: response.status,
    headers: { "Cache-Control": "no-store" },
  });
}

export async function establishSession(upstream: Response) {
  if (!upstream.ok) return forward(upstream);
  const tokens: Tokens = await upstream.json();
  const response = NextResponse.json(tokens.user, { headers: { "Cache-Control": "no-store" } });
  const options = {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
  };
  response.cookies.set(ACCESS_COOKIE, tokens.accessToken, {
    ...options,
    path: "/api",
    maxAge: tokens.expiresIn,
  });
  response.cookies.set(REFRESH_COOKIE, tokens.refreshToken, {
    ...options,
    path: "/api/auth",
    maxAge: REFRESH_TTL,
  });
  return response;
}

export function clearSession(response: NextResponse) {
  const options = {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
    maxAge: 0,
  };
  response.cookies.set(ACCESS_COOKIE, "", { ...options, path: "/api" });
  response.cookies.set(REFRESH_COOKIE, "", { ...options, path: "/api/auth" });
  return response;
}

// The backend rotates refresh tokens once. Share concurrent requests within this
// Next server so two tabs cannot consume the same token at the same time.
// Multi-instance deployments need a shared session/lock store before scaling.
const refreshing = new Map<string, Promise<Response>>();

export async function refreshSession(token: string) {
  let pending = refreshing.get(token);
  if (!pending) {
    pending = backend("auth/refresh", {
      method: "POST",
      body: JSON.stringify({ refreshToken: token }),
    });
    refreshing.set(token, pending);
    void pending.finally(() => {
      const timer = setTimeout(() => refreshing.delete(token), 5_000);
      timer.unref();
    });
  }
  return (await pending).clone();
}

export async function readJson(request: NextRequest) {
  if (!request.headers.get("content-type")?.startsWith("application/json")) {
    return { error: problem(415, "Expected application/json.") };
  }
  const reader = request.body?.getReader();
  if (!reader) return { error: problem(400, "Expected a JSON object.") };
  const decoder = new TextDecoder();
  let raw = "";
  let bytes = 0;
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    bytes += chunk.value.byteLength;
    if (bytes > 8192) {
      await reader.cancel();
      return { error: problem(413, "Request body is too large.") };
    }
    raw += decoder.decode(chunk.value, { stream: true });
  }
  raw += decoder.decode();
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return { error: problem(400, "Expected a JSON object.") };
    }
    return { body: value as Record<string, unknown> };
  } catch {
    return { error: problem(400, "Invalid JSON.") };
  }
}
