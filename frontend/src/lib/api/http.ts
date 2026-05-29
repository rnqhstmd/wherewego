import { cookies } from "next/headers";

import type { ApiResponse } from "./types";
import { ApiError } from "./http-client";

export { ApiError, apiFetch } from "./http-client";

/**
 * 백엔드 직접 호출용 베이스 URL.
 * Server Component / Server Action 환경에서만 사용한다.
 */
const BACKEND_BASE_URL =
  process.env.BACKEND_BASE_URL ?? "http://localhost:8080";

const API_PREFIX = "/api/v1";

/**
 * 백엔드 인증에 필요한 쿠키만 화이트리스트로 포워딩한다.
 * 이름은 backend `AuthCookieFactory.ACCESS_TOKEN` / `REFRESH_TOKEN` 과 동일하다.
 * 그 외 쿠키(분석, 디자인 툴, 세션 등)는 백엔드로 흘려보내지 않는다.
 */
const FORWARDED_COOKIE_NAMES: ReadonlySet<string> = new Set([
  "access_token",
  "refresh_token",
]);

/**
 * Server Component / Server Action에서 백엔드를 직접 호출한다.
 * Next.js 16의 비동기 `cookies()`로 인증 쿠키만 화이트리스트 부착하여 인증을 유지한다.
 */
export async function apiFetchServer<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const cookieStore = await cookies();
  const cookieHeader = cookieStore
    .getAll()
    .filter((c) => FORWARDED_COOKIE_NAMES.has(c.name))
    .map((c) => `${c.name}=${encodeURIComponent(c.value)}`)
    .join("; ");
  // FormData 요청은 fetch/브라우저가 multipart boundary 를 포함한 Content-Type 을
  // 자동 설정하도록 직접 부착하지 않는다. JSON 요청은 기존대로 부착한다.
  const isFormData = init?.body instanceof FormData;
  const res = await fetch(`${BACKEND_BASE_URL}${API_PREFIX}${path}`, {
    ...init,
    headers: {
      ...(isFormData ? {} : { "Content-Type": "application/json" }),
      Cookie: cookieHeader,
      ...(init?.headers ?? {}),
    },
    cache: "no-store",
  });
  return parseResponse<T>(res);
}

async function parseResponse<T>(res: Response): Promise<T> {
  if (res.status === 204) {
    return undefined as T;
  }
  let body: ApiResponse<T> | null = null;
  const text = await res.text();
  if (text.length > 0) {
    body = JSON.parse(text) as ApiResponse<T>;
  }
  if (!res.ok || body?.meta?.result === "FAIL") {
    const code = body?.meta?.errorCode ?? `HTTP_${res.status}`;
    const message =
      body?.meta?.message ?? `요청이 실패했습니다 (status=${res.status}).`;
    throw new ApiError(code, message, res.status);
  }
  return (body?.data as T) ?? (undefined as T);
}
