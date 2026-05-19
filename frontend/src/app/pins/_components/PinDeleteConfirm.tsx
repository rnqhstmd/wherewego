"use client";

import { useEffect, useRef } from "react";

import type { PinSummaryResponse } from "@/lib/api/types";

interface PinDeleteConfirmProps {
  pin: PinSummaryResponse;
  onCancel: () => void;
  onConfirm: () => void;
}

export function PinDeleteConfirm({
  pin,
  onCancel,
  onConfirm,
}: PinDeleteConfirmProps) {
  const dialogRef = useRef<HTMLDialogElement | null>(null);

  useEffect(() => {
    const node = dialogRef.current;
    if (!node) return;
    if (!node.open) {
      node.showModal();
    }
    // cleanup에서 dialog.close()를 호출하지 않는다.
    // Strict Mode dev의 mount→cleanup→mount cycle에서 close가 race condition을
    // 만들어 모달이 사라진 듯 보이는 문제가 있었다 — 실제 unmount는 React가 처리.
  }, []);

  return (
    <dialog
      ref={dialogRef}
      onClose={onCancel}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      className="m-auto w-full max-w-sm rounded-2xl border border-zinc-200 bg-white p-0 text-zinc-900 shadow-xl backdrop:bg-black/40 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-50"
    >
      <div className="flex flex-col gap-5 p-6">
        <header className="flex flex-col gap-1">
          <h2 className="text-lg font-semibold">이 핀을 삭제할까요?</h2>
          <p className="truncate text-sm text-zinc-500 dark:text-zinc-400">
            {pin.placeName}
          </p>
        </header>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          삭제하면 그룹원 모두의 목록에서 사라집니다. 되돌릴 수 없습니다.
        </p>
        <footer className="flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="inline-flex h-10 items-center rounded-full border border-zinc-200 px-4 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
          >
            취소
          </button>
          <button
            type="button"
            onClick={onConfirm}
            className="inline-flex h-10 items-center rounded-full bg-red-600 px-4 text-sm font-medium text-white transition-colors hover:bg-red-500"
          >
            삭제
          </button>
        </footer>
      </div>
    </dialog>
  );
}
