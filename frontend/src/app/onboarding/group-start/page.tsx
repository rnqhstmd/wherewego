import { redirect } from "next/navigation";
import { requireAuth } from "@/lib/auth/guard";
import { getMyActiveGroup } from "@/lib/api/group";
import { ApiError } from "@/lib/api/http-client";
import { GroupStartClient } from "./GroupStartClient";

export const dynamic = "force-dynamic";

/**
 * /onboarding/group-start 라우트 진입점 (Server Component).
 *
 * - 미인증: requireAuth가 /login으로 redirect.
 * - 이미 활성 그룹 보유: /map으로 redirect (EC-009 재로그인 등 직접 진입 차단).
 * - 그 외: GroupStartClient 렌더.
 */
export default async function GroupStartPage() {
  await requireAuth();
  try {
    const group = await getMyActiveGroup();
    if (group !== null) {
      redirect("/map");
    }
  } catch (e) {
    // 401은 requireAuth에서 이미 처리됨. redirect()는 throw로 동작하므로 재전파.
    if (e instanceof ApiError && e.status === 401) {
      // unreachable — requireAuth가 먼저 redirect
    } else {
      throw e;
    }
  }
  return <GroupStartClient />;
}
