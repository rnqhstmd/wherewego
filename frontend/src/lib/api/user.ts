import { apiFetch } from "./http-client";
import type { UserResponse } from "./auth";

/**
 * 현재 로그인 사용자 정보를 조회한다. (client-side)
 */
export async function getCurrentUser(): Promise<UserResponse> {
  return apiFetch<UserResponse>("/users/me");
}

/**
 * 현재 로그인 사용자의 닉네임을 변경한다. (client-side)
 */
export async function updateNickname(nickname: string): Promise<UserResponse> {
  return apiFetch<UserResponse>("/users/me", {
    method: "PUT",
    body: JSON.stringify({ nickname }),
  });
}
