import { apiFetchServer } from "./http";
import type {
  CreatePinInput,
  PinListResponse,
  PinSummaryResponse,
  PinTag,
  UpdatePinResponse,
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
 * Phase 13 (FR-PIN-9b~d): 추억핀 사진 업로드.
 *
 * 멀티파트로 전송하므로 `FormData` 에 `file` 파트를 담아 보낸다. `apiFetchServer` 는
 * body 가 `FormData` 이면 `Content-Type` 을 직접 부착하지 않아 fetch 가 boundary 를
 * 자동 설정한다(AC-17). `JSON.stringify` 는 사용하지 않는다.
 *
 * 응답은 사진 URL 이 채워진 갱신 `PinSummaryResponse`.
 */
export async function uploadPinPhoto(
  groupId: number,
  pinId: number,
  file: File,
): Promise<PinSummaryResponse> {
  const form = new FormData();
  form.append("file", file);
  return apiFetchServer<PinSummaryResponse>(
    `/groups/${groupId}/pins/${pinId}/photo`,
    {
      method: "POST",
      body: form,
    },
  );
}

/**
 * Phase 13 (FR-PIN-10a/b): 추억핀 사진 삭제.
 *
 * 백엔드는 204 가 아니라 사진 필드가 비워진 갱신 `PinSummaryResponse` 를 반환한다.
 */
export async function deletePinPhoto(
  groupId: number,
  pinId: number,
): Promise<PinSummaryResponse> {
  return apiFetchServer<PinSummaryResponse>(
    `/groups/${groupId}/pins/${pinId}/photo`,
    {
      method: "DELETE",
    },
  );
}
