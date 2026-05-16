"use client";

import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

interface PinCardProps {
  pin: PinSummaryResponse;
  onEdit: (pin: PinSummaryResponse) => void;
  onDelete: (pin: PinSummaryResponse) => void;
  disabled?: boolean;
}

const TAG_STYLES: Record<PinTag, string> = {
  PLACE:
    "bg-blue-100 text-blue-700 dark:bg-blue-950/60 dark:text-blue-300",
  MEMORY:
    "bg-pink-100 text-pink-700 dark:bg-pink-950/60 dark:text-pink-300",
};

const TAG_LABEL: Record<PinTag, string> = {
  PLACE: "장소",
  MEMORY: "추억",
};

function formatCreatedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

export function PinCard({ pin, onEdit, onDelete, disabled }: PinCardProps) {
  return (
    <article className="flex flex-col gap-3 rounded-xl border border-zinc-200 bg-white p-4 shadow-sm dark:border-zinc-800 dark:bg-zinc-900">
      <header className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-50">
            {pin.placeName}
          </h2>
          {pin.address ? (
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              {pin.address}
            </p>
          ) : null}
        </div>
        <span
          className={`inline-flex h-6 shrink-0 items-center rounded-full px-2 text-xs font-medium ${TAG_STYLES[pin.tag]}`}
        >
          {TAG_LABEL[pin.tag]}
        </span>
      </header>

      {pin.memo ? (
        <p className="whitespace-pre-wrap rounded-md bg-zinc-50 px-3 py-2 text-sm text-zinc-700 dark:bg-zinc-950 dark:text-zinc-300">
          {pin.memo}
          {pin.memoSource === "MANUAL" ? (
            <span className="ml-2 inline-flex h-5 items-center rounded-full bg-zinc-200 px-2 text-[10px] font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">
              직접 작성
            </span>
          ) : null}
        </p>
      ) : null}

      <footer className="flex items-center justify-between gap-2 text-xs text-zinc-500 dark:text-zinc-400">
        <div className="flex items-center gap-3">
          <span>{formatCreatedAt(pin.createdAt)}</span>
          {pin.instagramUrl ? (
            <a
              href={pin.instagramUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-zinc-700 underline-offset-2 hover:underline dark:text-zinc-300"
            >
              Instagram
            </a>
          ) : null}
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => onEdit(pin)}
            disabled={disabled}
            className="inline-flex h-8 items-center rounded-full border border-zinc-200 bg-white px-3 text-xs font-medium text-zinc-700 transition-colors hover:bg-zinc-50 disabled:opacity-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
          >
            편집
          </button>
          <button
            type="button"
            onClick={() => onDelete(pin)}
            disabled={disabled}
            className="inline-flex h-8 items-center rounded-full border border-red-200 bg-white px-3 text-xs font-medium text-red-600 transition-colors hover:bg-red-50 disabled:opacity-50 dark:border-red-900/60 dark:bg-zinc-900 dark:text-red-300 dark:hover:bg-red-950/40"
          >
            삭제
          </button>
        </div>
      </footer>
    </article>
  );
}
