import { requireAuth } from "@/lib/auth/guard";
import { NewGroupClient } from "./NewGroupClient";

/**
 * `/groups/new` — 신규 그룹 생성 화면.
 * 인증 필수, 그룹 보유자도 진입 가능 (추가 그룹 생성 가능).
 */
export default async function NewGroupPage() {
  await requireAuth();
  return <NewGroupClient />;
}
