import { cookies } from "next/headers";

import type { ApiResponse } from "./types";

/**
 * 백엔드 직접 호출용 베이스 URL.
 * Server Component / Server Action 환경에서만 사용한다.
 */
const BACKEND_BASE_URL =
  process.env.BACKEND_BASE_URL ?? "http://localhost:8080";

const API_PREFIX = "/api/v1";

/**
 * 백엔드 ApiResponse FAIL 응답을 표현하는 에러.
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

/**
 * 클라이언트 측에서 BFF 프록시(`/api/v1/...`)를 호출한다.
 * 동일 오리진이므로 쿠키는 브라우저가 자동으로 부착한다.
 */
export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const res = await fetch(`${API_PREFIX}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
    cache: "no-store",
  });
  return parseResponse<T>(res);
}

/**
 * Server Component / Server Action에서 백엔드를 직접 호출한다.
 * Next.js 16의 비동기 `cookies()`로 요청 쿠키를 그대로 부착하여 인증을 유지한다.
 */
export async function apiFetchServer<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const cookieStore = await cookies();
  const cookieHeader = cookieStore
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");
  const res = await fetch(`${BACKEND_BASE_URL}${API_PREFIX}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
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
