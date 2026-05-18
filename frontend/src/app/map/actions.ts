"use server";

import { revalidatePath } from "next/cache";

import { ApiError } from "@/lib/api/http";
import { createPin, deletePin, updatePin } from "@/lib/api/pin";
import type {
  CreatePinInput,
  PinSummaryResponse,
  PinTag,
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

export type UpdatePinTagActionResult = CreatePinActionResult;

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

export type UpdatePinMemoActionResult = CreatePinActionResult;

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

export type UpdatePinCoordinateActionResult = CreatePinActionResult;

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
