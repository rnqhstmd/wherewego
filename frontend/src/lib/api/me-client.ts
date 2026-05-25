import { apiFetch } from "./http-client";

/**
 * 사용자 온보딩 진입 상태 응답.
 * 백엔드 `MeV1Dto.OnboardingStatusResponse` 와 1:1.
 *
 * - `hasActiveGroup`: 활성 그룹 보유 여부.
 * - `activeGroupMemberCount`: 활성 그룹의 멤버 수. 그룹 없으면 0. 짝꿍 합류 완료 = 2.
 * - `hasBotMapping`: 카카오톡 챗봇 연동 여부.
 */
export interface OnboardingStatusResponse {
  hasActiveGroup: boolean;
  activeGroupMemberCount: number;
  hasBotMapping: boolean;
}

/**
 * 현재 사용자의 온보딩 진입 상태를 조회한다. (client-side)
 * 서버에서 60초 Caffeine 캐시가 적용된다.
 */
export async function getOnboardingStatus(): Promise<OnboardingStatusResponse> {
  return apiFetch<OnboardingStatusResponse>("/me/onboarding-status");
}
