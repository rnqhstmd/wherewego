import { apiFetch } from "./http-client";

/**
 * 챗봇(카카오톡 'MayGo' 채널) 계정 연동용 1회성 코드 응답.
 * 백엔드 `BotV1Dto.LinkCodeResponse`와 1:1 대응. (6자리, 10분 TTL)
 */
export interface LinkCodeResponse {
  code: string;
  expiresAt: string;
}

/**
 * 챗봇 연동 코드 발급. 클라이언트 컴포넌트 전용.
 *
 * 사용자가 카카오톡 'MayGo' 채널에 발급된 6자리 코드를 전송하면
 * 백엔드가 카카오 식별자와 현재 사용자(쿠키 인증)를 매핑한다.
 */
export async function issueBotLinkCode(): Promise<LinkCodeResponse> {
  return apiFetch<LinkCodeResponse>("/bot/link-codes", {
    method: "POST",
  });
}
