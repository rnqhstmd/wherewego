import { apiFetchServer } from "./http";
import type { ActiveGroupResponse } from "./types";

/**
 * 현재 로그인 사용자의 활성 그룹을 조회한다.
 * 미가입 사용자는 `null`을 반환한다.
 *
 * **server-only**: `apiFetchServer`에 의존(next/headers). 클라이언트 컴포넌트에서 호출 금지.
 * 클라이언트용 그룹 생성/조회는 `./group-client.ts`를 사용.
 */
export async function getMyActiveGroup(): Promise<ActiveGroupResponse | null> {
  // 백엔드는 그룹 미가입 시 data:null 을 반환하지만 parseResponse 가 null→undefined 변환.
  // 호출자가 `if (group === null)` 으로 비교할 수 있도록 null 로 정규화한다.
  const result = await apiFetchServer<ActiveGroupResponse | null>("/groups/me");
  return result ?? null;
}
