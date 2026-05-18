"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import type { PinSummaryResponse, PinTag } from "@/lib/api/types";
import {
  ADDRESS_MAX_LENGTH,
  MEMO_MAX_LENGTH,
  PLACE_NAME_MAX_LENGTH,
} from "@/lib/pin/constants";

export interface PinEditPatch {
  placeName?: string;
  address?: string;
  tag?: PinTag;
  memo?: string;
  // Phase 2.8 범위 외: instagramUrl 수정 (별도 Phase)
}

interface PinEditDialogProps {
  pin: PinSummaryResponse;
  onClose: () => void;
  onSave: (patch: PinEditPatch) => void;
}

export function PinEditDialog({ pin, onClose, onSave }: PinEditDialogProps) {
  const dialogRef = useRef<HTMLDialogElement | null>(null);
  const [placeName, setPlaceName] = useState<string>(pin.placeName);
  const [address, setAddress] = useState<string>(pin.address ?? "");
  const [memo, setMemo] = useState<string>(pin.memo ?? "");
  const [tag, setTag] = useState<PinTag>(pin.tag);

  useEffect(() => {
    const node = dialogRef.current;
    if (!node) return;
    if (!node.open) {
      node.showModal();
    }
    return () => {
      if (node.open) {
        node.close();
      }
    };
  }, []);

  const placeNameLength = placeName.length;
  const trimmedPlaceName = placeName.trim();
  const isPlaceNameEmpty = trimmedPlaceName.length === 0;
  const isPlaceNameTooLong = placeNameLength > PLACE_NAME_MAX_LENGTH;

  const addressLength = address.length;
  const isAddressTooLong = addressLength > ADDRESS_MAX_LENGTH;

  const memoLength = memo.length;
  const isMemoTooLong = memoLength > MEMO_MAX_LENGTH;

  const initialMemo = pin.memo ?? "";
  const initialAddress = pin.address ?? "";
  // pin.placeName 은 백엔드에서 trim 된 값이므로 단방향 trim 으로 비교한다.
  const placeNameChanged = placeName.trim() !== pin.placeName;
  // address 는 빈 문자열을 "미변경" 으로 정규화한다 (Q5 정책).
  // 사용자가 기존 주소를 다 지우면 addressChanged=false 가 되어 빈 patch 전송을 차단한다.
  const trimmedAddress = address.trim();
  const addressChanged =
    trimmedAddress.length > 0 && trimmedAddress !== initialAddress.trim();
  // memo 는 trim 비교로 일관성 유지. trailing whitespace 만 다른 경우 미변경으로 처리.
  // 단, initialMemo 가 비어있지 않은데 사용자가 다 지우면 trim 후 빈 문자열로 잠금 해제 (BR-8).
  const memoChanged = memo.trim() !== initialMemo.trim();
  const tagChanged = tag !== pin.tag;
  const canSave =
    (placeNameChanged || addressChanged || memoChanged || tagChanged) &&
    !isPlaceNameEmpty &&
    !isPlaceNameTooLong &&
    !isAddressTooLong &&
    !isMemoTooLong;

  const placeNameCounterClassName = useMemo(() => {
    if (isPlaceNameTooLong) {
      return "text-red-600 dark:text-red-400";
    }
    if (placeNameLength >= PLACE_NAME_MAX_LENGTH - 50) {
      return "text-amber-600 dark:text-amber-400";
    }
    return "text-zinc-500 dark:text-zinc-400";
  }, [isPlaceNameTooLong, placeNameLength]);

  const addressCounterClassName = useMemo(() => {
    if (isAddressTooLong) {
      return "text-red-600 dark:text-red-400";
    }
    if (addressLength >= ADDRESS_MAX_LENGTH - 50) {
      return "text-amber-600 dark:text-amber-400";
    }
    return "text-zinc-500 dark:text-zinc-400";
  }, [isAddressTooLong, addressLength]);

  const memoCounterClassName = useMemo(() => {
    if (isMemoTooLong) {
      return "text-red-600 dark:text-red-400";
    }
    if (memoLength >= MEMO_MAX_LENGTH - 50) {
      return "text-amber-600 dark:text-amber-400";
    }
    return "text-zinc-500 dark:text-zinc-400";
  }, [isMemoTooLong, memoLength]);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canSave) return;
    const patch: PinEditPatch = {};
    if (placeNameChanged) {
      patch.placeName = placeName.trim();
    }
    if (addressChanged) {
      const trimmed = address.trim();
      if (trimmed.length > 0) {
        patch.address = trimmed;
      }
      // 빈 경우 키 생략 (Q5 — 미변경 시맨틱과 일치)
    }
    if (memoChanged) {
      patch.memo = memo.trim();
    }
    if (tagChanged) {
      patch.tag = tag;
    }
    onSave(patch);
  };

  return (
    <dialog
      ref={dialogRef}
      onClose={onClose}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      className="m-auto w-full max-w-md rounded-2xl border border-zinc-200 bg-white p-0 text-zinc-900 shadow-xl backdrop:bg-black/40 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-50"
    >
      <form
        method="dialog"
        onSubmit={handleSubmit}
        className="flex flex-col gap-5 p-6"
      >
        <header className="flex flex-col gap-1">
          <h2 className="text-lg font-semibold">핀 편집</h2>
          <p className="truncate text-sm text-zinc-500 dark:text-zinc-400">
            {pin.placeName}
          </p>
        </header>

        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <label
              htmlFor="pin-place-name"
              className="text-sm font-medium"
            >
              장소명
            </label>
            <span className={`text-xs ${placeNameCounterClassName}`}>
              {placeNameLength}/{PLACE_NAME_MAX_LENGTH}
            </span>
          </div>
          <input
            id="pin-place-name"
            type="text"
            value={placeName}
            onChange={(event) => setPlaceName(event.target.value)}
            className="w-full rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm leading-6 text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-400 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-50 dark:placeholder:text-zinc-500"
          />
          {isPlaceNameEmpty ? (
            <p className="text-xs text-red-600 dark:text-red-400">
              장소명을 입력해주세요
            </p>
          ) : isPlaceNameTooLong ? (
            <p className="text-xs text-red-600 dark:text-red-400">
              장소명은 최대 {PLACE_NAME_MAX_LENGTH}자까지 입력할 수 있습니다.
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <label
              htmlFor="pin-address"
              className="text-sm font-medium"
            >
              주소
            </label>
            <span className={`text-xs ${addressCounterClassName}`}>
              {addressLength}/{ADDRESS_MAX_LENGTH}
            </span>
          </div>
          <input
            id="pin-address"
            type="text"
            value={address}
            onChange={(event) => setAddress(event.target.value)}
            placeholder="예: 서울시 강남구..."
            className="w-full rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm leading-6 text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-400 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-50 dark:placeholder:text-zinc-500"
          />
          {isAddressTooLong ? (
            <p className="text-xs text-red-600 dark:text-red-400">
              주소는 최대 {ADDRESS_MAX_LENGTH}자까지 입력할 수 있습니다.
            </p>
          ) : (
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              빈 값으로 저장해도 기존 주소는 유지됩니다.
            </p>
          )}
        </div>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">태그</legend>
          <div className="flex items-center gap-2">
            <label className="inline-flex flex-1 items-center justify-center gap-2 rounded-full border border-zinc-200 px-3 py-2 text-sm font-medium has-[:checked]:border-blue-500 has-[:checked]:bg-blue-50 has-[:checked]:text-blue-700 dark:border-zinc-700 dark:has-[:checked]:border-blue-400 dark:has-[:checked]:bg-blue-950/40 dark:has-[:checked]:text-blue-300">
              <input
                type="radio"
                name="tag"
                value="PLACE"
                checked={tag === "PLACE"}
                onChange={() => setTag("PLACE")}
                className="sr-only"
              />
              장소
            </label>
            <label className="inline-flex flex-1 items-center justify-center gap-2 rounded-full border border-zinc-200 px-3 py-2 text-sm font-medium has-[:checked]:border-pink-500 has-[:checked]:bg-pink-50 has-[:checked]:text-pink-700 dark:border-zinc-700 dark:has-[:checked]:border-pink-400 dark:has-[:checked]:bg-pink-950/40 dark:has-[:checked]:text-pink-300">
              <input
                type="radio"
                name="tag"
                value="MEMORY"
                checked={tag === "MEMORY"}
                onChange={() => setTag("MEMORY")}
                className="sr-only"
              />
              추억
            </label>
          </div>
        </fieldset>

        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <label
              htmlFor="pin-memo"
              className="text-sm font-medium"
            >
              메모
            </label>
            <span className={`text-xs ${memoCounterClassName}`}>
              {memoLength}/{MEMO_MAX_LENGTH}
            </span>
          </div>
          <textarea
            id="pin-memo"
            value={memo}
            onChange={(event) => setMemo(event.target.value)}
            rows={5}
            maxLength={MEMO_MAX_LENGTH}
            placeholder="메모를 입력하거나 비워서 자동 메모 잠금을 해제하세요"
            className="w-full resize-none rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm leading-6 text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-400 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-50 dark:placeholder:text-zinc-500"
          />
          {isMemoTooLong ? (
            <p className="text-xs text-red-600 dark:text-red-400">
              메모는 최대 {MEMO_MAX_LENGTH}자까지 입력할 수 있습니다.
            </p>
          ) : (
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              비워두고 저장하면 챗봇 자동 메모를 다시 받을 수 있습니다.
            </p>
          )}
        </div>

        <footer className="flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-10 items-center rounded-full border border-zinc-200 px-4 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
          >
            취소
          </button>
          <button
            type="submit"
            disabled={!canSave}
            className="inline-flex h-10 items-center rounded-full bg-zinc-900 px-4 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
          >
            저장
          </button>
        </footer>
      </form>
    </dialog>
  );
}
