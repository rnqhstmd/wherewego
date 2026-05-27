import { apiFetchServer } from "./http";
import type {
  CreatePinInput,
  PinListResponse,
  PinSummaryResponse,
  PinTag,
  UpdatePinResponse,
  WantStatusResponse,
  WantToggleResponse,
} from "./types";

export interface PinPatch {
  memo?: string;
  tag?: PinTag;
  placeName?: string;
  address?: string;
  latitude?: number;
  longitude?: number;
}

export interface ListPinsOptions {
  tag?: PinTag;
  page?: number;
  size?: number;
  /**
   * Phase 12 (FR-PIN-12-8): 정렬 기준. `want_count` 지정 시 want_count DESC.
   * 미지정 또는 `created_at` 이면 기존 정렬(생성일 내림차순).
   */
  sort?: "want_count" | "created_at";
  /**
   * Phase 12 (FR-PIN-12-9): true 시 want_count >= 1 인 핀만 반환한다.
   * 다른 필터(tag 등)와 AND 결합된다.
   */
  interest?: boolean;
}

/**
 * 그룹에 속한 활성 핀 목록을 조회한다. `tag` 미지정 시 전체를 반환한다.
 *
 * 두 번째 인자는 `PinTag` 문자열(legacy) 또는 `ListPinsOptions` 객체(page/size 포함)로 받는다.
 */
export async function listPins(
  groupId: number,
  optionsOrTag?: ListPinsOptions | PinTag,
): Promise<PinListResponse> {
  const options: ListPinsOptions =
    typeof optionsOrTag === "string"
      ? { tag: optionsOrTag }
      : (optionsOrTag ?? {});

  // page 와 size 는 항상 함께 지정해야 한다 (백엔드 PIN_PAGE_PARAM_INVALID 매핑과 일관).
  if ((options.page !== undefined) !== (options.size !== undefined)) {
    throw new Error("page와 size는 함께 지정해야 합니다");
  }

  const params = new URLSearchParams();
  if (options.tag) params.set("tag", options.tag);
  if (options.page !== undefined) params.set("page", String(options.page));
  if (options.size !== undefined) params.set("size", String(options.size));
  if (options.sort) params.set("sort", options.sort);
  if (options.interest) params.set("interest", "true");

  const query = params.toString();
  return apiFetchServer<PinListResponse>(
    `/groups/${groupId}/pins${query ? `?${query}` : ""}`,
  );
}

/**
 * 그룹에 새 핀을 추가한다. Server Action에서 호출되어 JWT 쿠키가 자동 부착된다.
 */
export async function createPin(
  groupId: number,
  input: CreatePinInput,
): Promise<PinSummaryResponse> {
  return apiFetchServer<PinSummaryResponse>(
    `/groups/${groupId}/pins`,
    {
      method: "POST",
      body: JSON.stringify(input),
    },
  );
}

/**
 * 핀의 memo / tag 부분 수정.
 *
 * 빈 문자열 memo는 잠금 해제 신호이므로 그대로 전송한다.
 * `undefined`인 키만 제거하여 "키 없음" 의미를 보존한다.
 *
 * Phase 10 보강 (2026-05-24): 응답이 {@link UpdatePinResponse} 로 감싸진다.
 * {@code summary} 는 기존 핀 응답, {@code transitionedToMemoryNow} 는 동시 수정 분기용
 * (WISH/REEL → MEMORY 전환이 본 호출에서 발생했는지). 호출처는 응답의 summary 만 사용해도 되지만,
 * 동시 수정 케이스를 다루는 흐름(MapClient.handleVisitConfirm) 은 transitionedToMemoryNow 도 본다.
 */
export async function updatePin(
  groupId: number,
  pinId: number,
  patch: PinPatch,
): Promise<UpdatePinResponse> {
  const body = Object.fromEntries(
    Object.entries(patch).filter(([, value]) => value !== undefined),
  );
  return apiFetchServer<UpdatePinResponse>(
    `/groups/${groupId}/pins/${pinId}`,
    {
      method: "PATCH",
      body: JSON.stringify(body),
    },
  );
}

/**
 * 핀 소프트 삭제. 백엔드는 204 No Content를 반환한다.
 */
export async function deletePin(
  groupId: number,
  pinId: number,
): Promise<void> {
  await apiFetchServer<void>(`/groups/${groupId}/pins/${pinId}`, {
    method: "DELETE",
  });
}

/**
 * Phase 12 (FR-PIN-12-2): WANT(가고 싶어요) 토글.
 *
 * 동일 (pin, user) 키로 멱등 UNIQUE 가 적용되어 동시 호출도 안전하다.
 * 응답의 `wishConverted: true` 면 본 호출이 REEL → WISH 전환을 트리거한 것이며,
 * 클라이언트는 마커 펄스/안내 등 1회성 효과를 발사한다.
 *
 * 백엔드 측 에러:
 *  - PIN_WANT_FORBIDDEN_TAG (400): MEMORY 핀에는 WANT 불가
 *  - PIN_NOT_FOUND (404)
 *  - GROUP_NOT_MEMBER (403)
 */
export async function toggleWant(
  groupId: number,
  pinId: number,
): Promise<WantToggleResponse> {
  return apiFetchServer<WantToggleResponse>(
    `/groups/${groupId}/pins/${pinId}/want`,
    { method: "POST" },
  );
}

/**
 * Phase 12 (FR-PIN-12-2): 현재 핀의 WANT 상태 단건 조회.
 *
 * 모달 등에서 최신 wantCount/myWant 를 다시 확인할 때 사용한다.
 * 일반 핀 목록 조회는 `listPins` 응답에 이미 포함되어 있어 별도 호출 불요.
 */
export async function getWantStatus(
  groupId: number,
  pinId: number,
): Promise<WantStatusResponse> {
  return apiFetchServer<WantStatusResponse>(
    `/groups/${groupId}/pins/${pinId}/want`,
  );
}
