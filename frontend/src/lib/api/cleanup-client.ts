import { apiFetch } from "./http-client";
import type {
  CleanupCandidatesResponse,
  CleanupExecuteResponse,
} from "./types";

/**
 * Phase 12 (FR-PIN-12-23): 정리 후보 핀 조회 (client-side).
 *
 * <p>{@link apiFetch} 기반 same-origin 호출 → Next.js BFF route 가 백엔드로 프록시.
 * snooze 중이면 응답의 {@code totalCount=0}, {@code snoozedUntil} 만 채워진다.</p>
 */
export async function fetchCleanupCandidates(
  groupId: number,
  signal?: AbortSignal,
): Promise<CleanupCandidatesResponse> {
  return apiFetch<CleanupCandidatesResponse>(
    `/groups/${groupId}/cleanup/candidates`,
    { signal },
  );
}

/**
 * Phase 12 (FR-PIN-12-24): 정리 대상 핀 일괄 삭제 (client-side).
 *
 * <p>서버는 트랜잭션 내에서 후보 ID 를 재계산하므로 race-safe.
 * 응답의 {@code deletedCount} 는 이번 호출이 실제로 삭제한 핀 수.</p>
 */
export async function executeCleanup(
  groupId: number,
): Promise<CleanupExecuteResponse> {
  return apiFetch<CleanupExecuteResponse>(
    `/groups/${groupId}/cleanup/execute`,
    { method: "POST" },
  );
}
