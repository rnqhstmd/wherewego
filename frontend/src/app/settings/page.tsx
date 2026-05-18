import { requireAuth } from "@/lib/auth/guard";
import { getMyActiveGroup } from "@/lib/api/group";
import { SettingsClient } from "./SettingsClient";

export const dynamic = "force-dynamic";

/**
 * `/settings` — 사용자/그룹 설정 화면.
 *
 * - 인증 필수. 활성 그룹 없는 사용자도 진입 가능 (그룹 섹션만 숨김).
 * - 서버에서 user + activeGroup을 조회하여 props로 주입.
 */
export default async function SettingsPage() {
  const user = await requireAuth("/settings");
  const activeGroup = await getMyActiveGroup();
  return <SettingsClient user={user} activeGroup={activeGroup} />;
}
