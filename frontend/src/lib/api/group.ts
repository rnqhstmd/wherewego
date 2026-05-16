import { apiFetchServer } from "./http";
import type { ActiveGroupResponse } from "./types";

/**
 * 현재 로그인 사용자의 활성 그룹을 조회한다.
 * 미가입 사용자는 `null`을 반환한다.
 */
export async function getMyActiveGroup(): Promise<ActiveGroupResponse | null> {
  return apiFetchServer<ActiveGroupResponse | null>("/groups/me");
}
