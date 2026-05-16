"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

export interface PinEditPatch {
  memo?: string;
  tag?: PinTag;
}

interface PinEditDialogProps {
  pin: PinSummaryResponse;
  onClose: () => void;
  onSave: (patch: PinEditPatch) => void;
}

const MEMO_MAX_LENGTH = 500;

export function PinEditDialog({ pin, onClose, onSave }: PinEditDialogProps) {
  const dialogRef = useRef<HTMLDialogElement | null>(null);
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

  const memoLength = memo.length;
  const isMemoTooLong = memoLength > MEMO_MAX_LENGTH;

  const initialMemo = pin.memo ?? "";
  const memoChanged = memo !== initialMemo;
  const tagChanged = tag !== pin.tag;
  const canSave = (memoChanged || tagChanged) && !isMemoTooLong;

  const counterClassName = useMemo(() => {
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
    if (memoChanged) {
      patch.memo = memo;
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

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">태그</legend>
          <div className="flex items-center gap-2">
            <label className="inline-flex flex-1 items-center justify-center gap-2 rounded-full border border-zinc-200 px-3 py-2 text-sm font-medium has-checked:border-blue-500 has-checked:bg-blue-50 has-checked:text-blue-700 dark:border-zinc-700 dark:has-checked:border-blue-400 dark:has-checked:bg-blue-950/40 dark:has-checked:text-blue-300">
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
            <label className="inline-flex flex-1 items-center justify-center gap-2 rounded-full border border-zinc-200 px-3 py-2 text-sm font-medium has-checked:border-pink-500 has-checked:bg-pink-50 has-checked:text-pink-700 dark:border-zinc-700 dark:has-checked:border-pink-400 dark:has-checked:bg-pink-950/40 dark:has-checked:text-pink-300">
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
            <span className={`text-xs ${counterClassName}`}>
              {memoLength}/{MEMO_MAX_LENGTH}
            </span>
          </div>
          <textarea
            id="pin-memo"
            value={memo}
            onChange={(event) => setMemo(event.target.value)}
            rows={5}
            maxLength={MEMO_MAX_LENGTH + 100}
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
