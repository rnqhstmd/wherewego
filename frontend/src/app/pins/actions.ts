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
    const data = await updatePin(groupId, pinId, patch);
    revalidatePath("/pins");
    return { ok: true, data };
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
