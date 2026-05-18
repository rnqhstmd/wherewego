import { requireAuth } from "@/lib/auth/guard";
import { NicknameClient } from "./NicknameClient";

export const dynamic = "force-dynamic";

/**
 * /onboarding/nickname 라우트 진입점 (Server Component).
 *
 * - 미인증 사용자는 requireAuth가 /login으로 redirect.
 * - 현재 사용자 닉네임을 NicknameClient에 주입.
 */
export default async function NicknamePage() {
  const user = await requireAuth();
  return <NicknameClient initialNickname={user.nickname ?? ""} />;
}
