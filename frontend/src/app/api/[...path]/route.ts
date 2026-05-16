import type { NextRequest } from "next/server";

const BACKEND_BASE_URL =
  process.env.BACKEND_BASE_URL ?? "http://localhost:8080";

/**
 * 백엔드로 전달하지 않을 hop-by-hop 헤더 또는 호스트 의존 헤더.
 */
const STRIPPED_REQUEST_HEADERS = new Set([
  "host",
  "connection",
  "content-length",
  "accept-encoding",
]);

/**
 * 응답에서 그대로 전달하지 않을 헤더.
 * Set-Cookie는 별도로 처리한다.
 */
const STRIPPED_RESPONSE_HEADERS = new Set([
  "content-encoding",
  "content-length",
  "transfer-encoding",
  "connection",
]);

function forwardRequestHeaders(source: Headers): Headers {
  const next = new Headers();
  source.forEach((value, key) => {
    if (!STRIPPED_REQUEST_HEADERS.has(key.toLowerCase())) {
      next.set(key, value);
    }
  });
  return next;
}

function forwardResponseHeaders(source: Headers): Headers {
  const next = new Headers();
  source.forEach((value, key) => {
    if (!STRIPPED_RESPONSE_HEADERS.has(key.toLowerCase())) {
      next.append(key, value);
    }
  });
  return next;
}

/**
 * URL 인코딩 변형(%2e%2e, %2E%2E 등)까지 차단하기 위해
 * 디코드 후 경로 구분자/상대 경로 토큰을 모두 거부한다.
 */
function isUnsafeSegment(seg: string): boolean {
  let decoded = seg;
  try {
    decoded = decodeURIComponent(seg);
  } catch {
    return true;
  }
  if (decoded === "." || decoded === "..") return true;
  if (decoded.includes("..")) return true;
  if (decoded.includes("/") || decoded.includes("\\")) return true;
  return false;
}

async function proxy(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await ctx.params;
  if (path.some(isUnsafeSegment)) {
    return new Response(
      JSON.stringify({
        meta: {
          result: "FAIL",
          errorCode: "BAD_REQUEST",
          message: "invalid path",
        },
      }),
      {
        status: 400,
        headers: { "Content-Type": "application/json" },
      },
    );
  }
  const url = `${BACKEND_BASE_URL}/api/${path.join("/")}${req.nextUrl.search}`;

  const method = req.method.toUpperCase();
  const hasBody = method !== "GET" && method !== "HEAD";

  const init: RequestInit = {
    method,
    headers: forwardRequestHeaders(req.headers),
    body: hasBody ? await req.arrayBuffer() : undefined,
    cache: "no-store",
    redirect: "manual",
  };

  const upstream = await fetch(url, init);
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: forwardResponseHeaders(upstream.headers),
  });
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
export const HEAD = proxy;
export const OPTIONS = proxy;
