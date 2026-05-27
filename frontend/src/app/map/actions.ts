"use server";

import { revalidatePath } from "next/cache";

import { ApiError } from "@/lib/api/http";
import { createPin, deletePin, toggleWant, updatePin } from "@/lib/api/pin";
import type {
  CreatePinInput,
  PinSummaryResponse,
  PinTag,
  UpdatePinResponse,
  WantToggleResponse,
} from "@/lib/api/types";

export type CreatePinActionResult =
  | { ok: true; data: PinSummaryResponse }
  | { ok: false; code: string; message: string };

/**
 * 핀 추가 Server Action.
 *
 * MUST-1: revalidatePath 호출하지 않고 응답을 그대로 반환한다.
 * 클라이언트가 reducer 의 add 액션으로 dispatch 하여 마커 인스턴스 캐시를 유지한다.
 */
export async function createPinAction(
  groupId: number,
  input: CreatePinInput,
): Promise<CreatePinActionResult> {
  try {
    const data = await createPin(groupId, input);
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}

/**
 * Phase 10 보강 (2026-05-24): PATCH 응답이 {@link UpdatePinResponse} 로 감싸진다.
 * - {@code data.summary} 는 기존 핀 응답 (대부분의 호출처에서 사용)
 * - {@code data.transitionedToMemoryNow} 는 WISH/REEL → MEMORY 전환이 본 호출에서
 *   발생했는지 (동시 수정 분기용). tag 외 필드 PATCH 에서는 항상 false.
 */
export type UpdatePinActionResult =
  | { ok: true; data: UpdatePinResponse }
  | { ok: false; code: string; message: string };

export type UpdatePinTagActionResult = UpdatePinActionResult;

/**
 * 핀 태그 변경 Server Action. `/pins/actions.ts::updatePinAction` 과 분리한다
 * (해당 함수는 revalidatePath("/pins")를 호출하므로 `/map`에서 호출 시 부작용).
 *
 * 응답을 그대로 반환 → 클라 useOptimistic 가 즉시 반영.
 */
export async function updatePinTagAction(
  groupId: number,
  pinId: number,
  tag: PinTag,
): Promise<UpdatePinTagActionResult> {
  try {
    const data = await updatePin(groupId, pinId, { tag });
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}

export type UpdatePinMemoActionResult = UpdatePinActionResult;

/**
 * 핀 메모 변경 Server Action (FR-MMO-2).
 *
 * `updatePin(groupId, pinId, { memo })`로 위임한다. 빈 문자열도 그대로 전송하여
 * 잠금 해제(BR-3) 신호로 동작하며, `lib/api/pin.ts::updatePin`이 빈 문자열을
 * 보존하므로 추가 변환은 하지 않는다.
 *
 * 성공 시에만 `revalidatePath('/pins')`를 호출하여 핀 목록 페이지 캐시를 갱신한다.
 * `/map`은 클라 state 우선 유지 정책이라 별도 revalidate는 하지 않는다.
 */
export async function updatePinMemoAction(
  groupId: number,
  pinId: number,
  memo: string,
): Promise<UpdatePinMemoActionResult> {
  try {
    const data = await updatePin(groupId, pinId, { memo });
    // revalidatePath 실패가 저장 성공 응답을 가리지 않도록 분리
    try {
      revalidatePath("/pins");
    } catch (revalidateError) {
      console.error(
        "revalidatePath('/pins') 실패 (저장은 성공)",
        revalidateError,
      );
    }
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}

export type UpdatePinCoordinateActionResult = UpdatePinActionResult;

/**
 * 핀 좌표 변경 Server Action (Phase 2.10 FR-PIN-COORD).
 *
 * `/map`은 useOptimistic이 즉시 갱신하므로 `revalidatePath('/map')`은 호출하지 않는다.
 * `/pins` UI는 좌표를 직접 표시하지 않으나 정합성을 위해 갱신한다.
 * revalidatePath 실패가 저장 성공 응답을 가리지 않도록 try/catch 분리.
 */
export async function updatePinCoordinateAction(
  groupId: number,
  pinId: number,
  latitude: number,
  longitude: number,
): Promise<UpdatePinCoordinateActionResult> {
  try {
    const data = await updatePin(groupId, pinId, { latitude, longitude });
    try {
      revalidatePath("/pins");
    } catch (revalidateError) {
      console.error(
        "revalidatePath('/pins') 실패 (좌표 변경은 성공)",
        revalidateError,
      );
    }
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}

export type UpdatePinPlaceNameActionResult = UpdatePinActionResult;

/**
 * 핀 장소 이름 변경 Server Action (Phase 2.8).
 *
 * 백엔드 PinUpdateCommand 의 placeNameProvided 분기로 위임.
 * `/map`은 useOptimistic 즉시 갱신, `/pins`만 revalidatePath.
 */
export async function updatePinPlaceNameAction(
  groupId: number,
  pinId: number,
  placeName: string,
): Promise<UpdatePinPlaceNameActionResult> {
  try {
    const data = await updatePin(groupId, pinId, { placeName });
    try {
      revalidatePath("/pins");
    } catch (revalidateError) {
      console.error(
        "revalidatePath('/pins') 실패 (장소명 변경은 성공)",
        revalidateError,
      );
    }
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}

export type ToggleWantActionResult =
  | { ok: true; data: WantToggleResponse }
  | { ok: false; code: string; message: string };

/**
 * Phase 12 (FR-PIN-12-2): WANT(가고 싶어요) 토글 Server Action.
 *
 * `/map` 은 클라 useOptimistic 으로 wantCount/myWant 를 즉시 반영하므로
 * revalidatePath 호출 없이 응답을 그대로 반환한다. (`updatePinTagAction` 패턴 답습)
 * 응답의 `wishConverted` 가 true 면 호출자(MapClient)가 마커 펄스를 1회 트리거한다.
 */
export async function toggleWantAction(
  groupId: number,
  pinId: number,
): Promise<ToggleWantActionResult> {
  try {
    const data = await toggleWant(groupId, pinId);
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}

export type DeletePinActionResult =
  | { ok: true }
  | { ok: false; code: string; message: string };

/**
 * 핀 삭제 Server Action (Phase 2.8 FR-7).
 *
 * /map은 클라이언트 state(useOptimistic)로 마커 인스턴스 캐시 유지가 필요.
 * revalidatePath('/map') 호출 시 mapbox-gl 컴포넌트 재마운트 발생 → MUST-1 위배.
 * /pins 라우트만 try/catch로 fail-safe revalidate (updatePinMemoAction 패턴).
 */
export async function deletePinAction(
  groupId: number,
  pinId: number,
): Promise<DeletePinActionResult> {
  try {
    await deletePin(groupId, pinId);
    try {
      revalidatePath("/pins");
    } catch (revalidateError) {
      console.error(
        "revalidatePath('/pins') 실패 (삭제는 성공)",
        revalidateError,
      );
    }
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}
