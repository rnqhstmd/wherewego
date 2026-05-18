import { apiFetch } from "./http-client";

/**
 * 카카오 로그인 URL 조회 응답.
 */
export interface LoginUrlResponse {
  loginUrl: string;
}

/**
 * 백엔드 `UserResponse`와 1:1 대응.
 */
export interface UserResponse {
  id: number;
  nickname: string;
  profileImageUrl: string | null;
}

/**
 * 카카오 콜백 처리 요청 본문.
 */
export interface KakaoCallbackRequest {
  code: string;
}

/**
 * 카카오 로그인 진입 URL을 조회한다. (client-side)
 */
export async function getKakaoLoginUrl(): Promise<LoginUrlResponse> {
  return apiFetch<LoginUrlResponse>("/auth/kakao/login-url");
}

/**
 * 카카오 OAuth2 인가 코드를 백엔드로 전달하여 로그인을 완료한다.
 * 성공 시 Set-Cookie 헤더로 `access_token` / `refresh_token`이 부착된다.
 */
export async function postKakaoCallback(
  body: KakaoCallbackRequest,
): Promise<UserResponse> {
  return apiFetch<UserResponse>("/auth/kakao/callback", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
