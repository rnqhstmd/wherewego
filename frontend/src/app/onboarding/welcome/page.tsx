import { requireAuth } from "@/lib/auth/guard";
import { getOnboardingStatusServer } from "@/lib/api/me-server";
import { WelcomeWizardClient } from "./WelcomeWizardClient";

export const dynamic = "force-dynamic";

/**
 * `/onboarding/welcome` — 신규 가입 위저드 (Phase 11 PR-B).
 *
 * 3단계: 그룹 만들기 → 초대 링크 공유 → 챗봇 연동.
 * 각 단계 건너뛰기 허용. 진입 자체는 한 번만 자동 강제(callback 에서 분기).
 * 사용자가 직접 진입 시 항상 표시되어 재진입 가능.
 *
 * 초기 상태는 서버에서 조회하여 이미 그룹/봇 보유한 사용자는 해당 단계를 자동 건너뛴다.
 */
export default async function OnboardingWelcomePage() {
  await requireAuth("/onboarding/welcome");
  const initialStatus = await getOnboardingStatusServer();
  return <WelcomeWizardClient initialStatus={initialStatus} />;
}
