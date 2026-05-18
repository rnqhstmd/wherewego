import { requireAuth } from "@/lib/auth/guard";
import { getMyActiveGroup } from "@/lib/api/group";
import { GroupsClient } from "./GroupsClient";

export const dynamic = "force-dynamic";

/**
 * /groups 라우트 진입점 (Server Component).
 *
 * - 미인증: requireAuth가 /login으로 redirect.
 * - 활성 그룹 조회 결과를 GroupsClient에 주입 (null 가능).
 */
export default async function GroupsPage() {
  const user = await requireAuth();
  const activeGroup = await getMyActiveGroup();
  return <GroupsClient user={user} activeGroup={activeGroup} />;
}
