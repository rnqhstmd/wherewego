import { requireAuthAndGroup } from "@/lib/auth/guard";
import { InviteLinkClient } from "./InviteLinkClient";

export const dynamic = "force-dynamic";

/**
 * `/groups/invite` — 활성 그룹 초대 링크 발급 + 공유 화면.
 * 인증 + 활성 그룹 필수. 진입 시 자동으로 초대 링크를 발급한다.
 */
export default async function InvitePage() {
  const group = await requireAuthAndGroup("/groups/invite");
  return <InviteLinkClient groupId={group.groupId} />;
}
