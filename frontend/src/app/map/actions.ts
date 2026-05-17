"use server";

import { ApiError } from "@/lib/api/http";
import { createPin, updatePin } from "@/lib/api/pin";
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
