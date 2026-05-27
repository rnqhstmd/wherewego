import { apiFetch } from "./http-client";
import type { CleanupSnoozeResponse } from "./types";

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

/**
 * Phase 12 (FR-PIN-12-25): 오래된 핀 정리 배너 7일 snooze (client-side).
 *
 * <p>현재 사용자의 cleanup_snoozed_until 을 NOW()+7일로 갱신한다.
 * 기존 snooze 가 있어도 덮어쓴다 (재snooze 가능).</p>
 */
export async function snoozeCleanup(): Promise<CleanupSnoozeResponse> {
  return apiFetch<CleanupSnoozeResponse>("/users/me/cleanup-snooze", {
    method: "POST",
  });
}
