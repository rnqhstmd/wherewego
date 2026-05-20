import { redirect } from "next/navigation";
import { requireAuth } from "@/lib/auth/guard";
import { getMyActiveGroup } from "@/lib/api/group";
import { GroupsClient } from "./GroupsClient";

export const dynamic = "force-dynamic";

/**
 * /groups 라우트 진입점 (Server Component).
 *
 * - 미인증: requireAuth가 /login으로 redirect.
 * - 활성 그룹 보유 시: 1인 1활성 그룹 정책(BR-1)상 선택지가 없으므로 /map으로 즉시 진입.
 * - 활성 그룹 없음: 그룹 생성/대기 화면(GroupsClient) 노출.
 */
export default async function GroupsPage() {
  const user = await requireAuth();
  const activeGroup = await getMyActiveGroup();
  if (activeGroup) {
    redirect("/map");
  }
  return <GroupsClient user={user} activeGroup={activeGroup} />;
}
