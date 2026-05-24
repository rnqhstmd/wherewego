"use server";

import { revalidatePath } from "next/cache";

import { ApiError } from "@/lib/api/http";
import { deletePin, updatePin, type PinPatch } from "@/lib/api/pin";
import type { PinSummaryResponse } from "@/lib/api/types";

export type UpdatePinActionResult =
  | { ok: true; data: PinSummaryResponse }
  | { ok: false; code: string; message: string };

export type DeletePinActionResult =
  | { ok: true }
  | { ok: false; code: string; message: string };

export async function updatePinAction(
  groupId: number,
  pinId: number,
  patch: PinPatch,
): Promise<UpdatePinActionResult> {
  try {
    // Phase 10 보강 (2026-05-24): updatePin 응답이 UpdatePinResponse 로 감싸졌으나
    // /pins UI 는 transitionedToMemoryNow 를 사용하지 않으므로 summary 만 추출하여
    // 기존 호출처(PinListClient.handleSave) 의 PinSummaryResponse 시그니처를 유지한다.
    const response = await updatePin(groupId, pinId, patch);
    revalidatePath("/pins");
    return { ok: true, data: response.summary };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}

export async function deletePinAction(
  groupId: number,
  pinId: number,
): Promise<DeletePinActionResult> {
  try {
    await deletePin(groupId, pinId);
    revalidatePath("/pins");
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, code: error.code, message: error.message };
    }
    throw error;
  }
}
