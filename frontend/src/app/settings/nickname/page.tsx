import { requireAuth } from "@/lib/auth/guard";
import { NicknameClient } from "@/app/onboarding/nickname/NicknameClient";

export const dynamic = "force-dynamic";

/**
 * /settings/nickname — 마이페이지에서 진입하는 닉네임 수정 화면.
 *
 * /onboarding/nickname (첫 가입 흐름)과 같은 컴포넌트를 mode="edit"로 재사용.
 * 저장 후엔 /settings로 돌아간다.
 */
export default async function SettingsNicknamePage() {
  const user = await requireAuth();
  return <NicknameClient initialNickname={user.nickname ?? ""} mode="edit" />;
}
