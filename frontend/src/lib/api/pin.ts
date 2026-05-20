import { apiFetchServer } from "./http";
import type {
  CreatePinInput,
  PinListResponse,
  PinSummaryResponse,
  PinTag,
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
 */
export async function updatePin(
  groupId: number,
  pinId: number,
  patch: PinPatch,
): Promise<PinSummaryResponse> {
  const body = Object.fromEntries(
    Object.entries(patch).filter(([, value]) => value !== undefined),
  );
  return apiFetchServer<PinSummaryResponse>(
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
