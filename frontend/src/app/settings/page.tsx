import { requireAuth } from "@/lib/auth/guard";
import { getMyActiveGroup } from "@/lib/api/group";
import { getOnboardingStatusServer } from "@/lib/api/me-server";
import type { OnboardingStatusResponse } from "@/lib/api/me-client";
import { SettingsClient } from "./SettingsClient";

export const dynamic = "force-dynamic";

/**
 * `/settings` — 사용자/그룹 설정 화면.
 *
 * - 인증 필수. 활성 그룹 없는 사용자도 진입 가능 (그룹 섹션만 숨김).
 * - 서버에서 user + activeGroup + onboardingStatus 를 조회하여 props 로 주입.
 *   onboardingStatus 는 챗봇 연동 / 초대 항목의 강등 표시(AC-8)에 사용된다.
 *   PR-B 머지 전이라면 fetch 실패 시 안전한 폴백으로 강등 미적용 상태를 사용한다.
 */
export default async function SettingsPage() {
  const user = await requireAuth("/settings");
  const [activeGroup, onboardingStatus] = await Promise.all([
    getMyActiveGroup(),
    getOnboardingStatusServer().catch((): OnboardingStatusResponse => ({
      hasActiveGroup: false,
      activeGroupMemberCount: 0,
      hasBotMapping: false,
    })),
  ]);
  return (
    <SettingsClient
      user={user}
      activeGroup={activeGroup}
      onboardingStatus={onboardingStatus}
    />
  );
}
