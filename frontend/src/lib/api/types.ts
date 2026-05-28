/**
 * 백엔드 ApiResponse 공통 래퍼 (`com.wherewego.interfaces.api.ApiResponse`)와 1:1 대응.
 */

export type PinTag = "REEL" | "WISH" | "MEMORY";

export type MemoSource = "AUTO" | "MANUAL";

export interface ApiMeta {
  result: "SUCCESS" | "FAIL";
  errorCode?: string;
  message?: string;
}

export interface ApiResponse<T> {
  meta: ApiMeta;
  data?: T;
}

export interface PinSummaryResponse {
  id: number;
  groupId: number;
  createdBy: number;
  createdByNickname: string | null;
  placeName: string;
  address: string | null;
  latitude: number;
  longitude: number;
  instagramUrl: string | null;
  memo: string | null;
  memoSource: MemoSource | null;
  tag: PinTag;
  createdAt: string;
  /**
   * WISH/REEL → MEMORY 전환 시각(ISO 8601). MEMORY 가 아니거나 V010 이전 기존 MEMORY 핀이면 null.
   * 핀 팝업 날짜 표시 정책: tag === "MEMORY" && visitedAt 있으면 visitedAt, 그 외 createdAt 폴백.
   */
  visitedAt: string | null;
  memoUpdatedBy: number | null;
  memoUpdatedByNickname: string | null;
}

export interface PinListResponse {
  items: PinSummaryResponse[];
  totalCount?: number;
  hasNext?: boolean;
}

/**
 * Phase 12 (FR-PIN-12-23): `GET /api/v1/groups/{gid}/cleanup/candidates` 응답.
 *
 * - snooze 중인 사용자: totalCount=0, snoozedUntil=만료시각, items=[].
 * - snooze 없음: totalCount=N, snoozedUntil=null, items=N개 후보.
 */
export interface CleanupCandidatesResponse {
  totalCount: number;
  snoozedUntil: string | null;
  items: PinSummaryResponse[];
}

/**
 * Phase 12 (FR-PIN-12-24): `POST /api/v1/groups/{gid}/cleanup/execute` 응답.
 *
 * `deletedCount` 는 이번 호출이 실제로 삭제한 핀 수 (이미 삭제됐던 행 제외).
 */
export interface CleanupExecuteResponse {
  deletedCount: number;
}

/**
 * Phase 12 (FR-PIN-12-25): `POST /api/v1/users/me/cleanup-snooze` 응답.
 *
 * `snoozedUntil` 은 갱신된 cleanup_snoozed_until (NOW()+7일, ISO 8601).
 */
export interface CleanupSnoozeResponse {
  snoozedUntil: string;
}

/**
 * Phase 10 보강 (2026-05-24): PATCH /pins/{id} 응답.
 *
 * <p>{@code transitionedToMemoryNow} 는 본 PATCH 가 WISH/REEL → MEMORY 전환을 실제로
 * 발생시켰는지를 나타낸다. 두 사용자가 동시에 같은 핀을 메모리로 전환하면 두 번째 PATCH 는
 * {@code false} 가 되어, 클라이언트가 confetti/메모 시트를 건너뛰고 안내 토스트만 노출한다.</p>
 *
 * <p>tag 외 필드(memo/coordinate/placeName)만 변경하는 PATCH 에서는 항상 {@code false}.</p>
 */
export interface UpdatePinResponse {
  summary: PinSummaryResponse;
  transitionedToMemoryNow: boolean;
}

export interface ActiveGroupResponse {
  groupId: number;
  name: string;
  memberCount: number;
  role: string;
  joinedAt: string;
}

/**
 * 핀 추가 요청 입력. 백엔드 `PinV1Dto.CreatePinRequest`와 1:1 대응.
 */
export interface CreatePinInput {
  placeName: string;
  address?: string | null;
  latitude: number;
  longitude: number;
  instagramUrl?: string | null;
  memo?: string | null;
  tag: PinTag;
}

/**
 * 장소 검색 결과 단건. 백엔드 `PlaceV1Dto.PlaceSearchItem`과 1:1 대응.
 */
export interface PlaceSearchItem {
  placeName: string;
  address: string | null;
  latitude: number;
  longitude: number;
}

export interface PlaceSearchResponse {
  items: PlaceSearchItem[];
}
