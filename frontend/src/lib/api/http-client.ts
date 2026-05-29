import type { ApiResponse } from "./types";

const API_PREFIX = "/api/v1";

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

export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  // FormData 요청은 브라우저가 multipart boundary 를 포함한 Content-Type 을
  // 자동 설정하도록 직접 부착하지 않는다. JSON 요청은 기존대로 부착한다.
  const isFormData = init?.body instanceof FormData;
  const res = await fetch(`${API_PREFIX}${path}`, {
    ...init,
    headers: {
      ...(isFormData ? {} : { "Content-Type": "application/json" }),
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
