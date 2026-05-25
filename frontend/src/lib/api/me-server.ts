import "server-only";

import { apiFetchServer } from "./http";
import type { OnboardingStatusResponse } from "./me-client";

/**
 * Server Component / Server Action 에서 온보딩 진입 상태를 조회한다.
 * 인증 쿠키는 `apiFetchServer` 가 자동으로 포워딩한다.
 *
 * 미인증(401)이면 ApiError 가 던져진다 — 호출자가 `requireAuth` 이후 호출하거나 try/catch 처리.
 */
export async function getOnboardingStatusServer(): Promise<OnboardingStatusResponse> {
  return apiFetchServer<OnboardingStatusResponse>("/me/onboarding-status");
}
