import { requireAuth } from "@/lib/auth/guard";
import { InviteCodeClient } from "./InviteCodeClient";

export const dynamic = "force-dynamic";

/**
 * `/onboarding/invite-code` — 초대 코드(=초대 토큰) 입력 화면.
 *
 * - 로그인 필수. 그룹 보유 사용자도 진입 가능 (다른 그룹 합류 가능 정책은 백엔드가 판단).
 * - `?token=` 쿼리가 있으면 InviteCodeClient에서 prefill.
 */
export default async function InviteCodePage() {
  await requireAuth("/onboarding/invite-code");
  return <InviteCodeClient />;
}
