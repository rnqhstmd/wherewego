import type { NextRequest } from "next/server";

/**
 * SSE 전용 BFF 프록시 라우트.
 *
 * <p>catch-all 라우트(`/api/[...path]`)는 fetch-then-respond 패턴과
 * `AbortSignal.timeout(5000)`을 사용하므로 long-lived SSE 스트림에 부적합하다.
 * 본 라우트는 Node.js runtime의 streaming 응답으로 백엔드 SSE를 그대로 파이프한다.</p>
 *
 * <p>Next.js App Router는 더 구체적인 라우트가 catch-all보다 우선하므로
 * 별도의 가드 없이 본 라우트가 `/api/v1/notifications/stream` 요청을 받는다.</p>
 */

const BACKEND_BASE_URL =
  process.env.BACKEND_BASE_URL ?? "http://localhost:8080";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const backendUrl = `${BACKEND_BASE_URL}/api/v1/notifications/stream`;

  // 쿠키 헤더 그대로 전달 (catch-all 라우트와 동일한 인증 방식)
  const cookieHeader = request.headers.get("cookie") ?? "";

  const upstream = await fetch(backendUrl, {
    method: "GET",
    headers: {
      Accept: "text/event-stream",
      "Cache-Control": "no-cache",
      Cookie: cookieHeader,
    },
    // 클라이언트 disconnect 시 upstream fetch 즉시 종료
    signal: request.signal,
  });

  if (!upstream.ok || !upstream.body) {
    return new Response(null, {
      status: upstream.status,
      // EventSource 재연결 시그널 약화 (401/403 시 빠른 종료 유도)
      headers: { Connection: "close" },
    });
  }

  // 응답 헤더 그대로 forwarding + 프록시 버퍼링 방지
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      "X-Accel-Buffering": "no",
      Connection: "keep-alive",
    },
  });
}
