import "server-only";

import { apiFetchServer } from "./http";
import type { UserResponse } from "./auth";

/**
 * Server Component / Server Action에서 현재 로그인 사용자 정보를 조회한다.
 * 인증 쿠키는 `apiFetchServer`가 자동으로 포워딩한다.
 */
export async function getCurrentUserServer(): Promise<UserResponse> {
  return apiFetchServer<UserResponse>("/users/me");
}
