"use client";

import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

interface PinCardProps {
  pin: PinSummaryResponse;
  onEdit: (pin: PinSummaryResponse) => void;
  onDelete: (pin: PinSummaryResponse) => void;
  disabled?: boolean;
}

// Tailwind v4 정적 매핑 — 동적 템플릿 금지.
const TAG_STYLES: Record<PinTag, string> = {
  REEL: "bg-pin-reel/10 text-pin-reel border border-pin-reel/30",
  WISH: "bg-pin-wish/10 text-pin-wish border border-pin-wish/30",
  MEMORY: "bg-pin-memory/10 text-pin-memory border border-pin-memory/30",
};

const TAG_LABEL: Record<PinTag, string> = {
  REEL: "발견",
  WISH: "위시",
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
  // M1 fallback (사용자 확인): 활성 세션에서 알 수 없는 enum 이 도착해도 일시적
  // 일관성을 위해 WISH 스타일/라벨로 표시한다.
  // Phase 7 사용자 확인된 안전장치 — 운영 관찰 목적
  const tagStyle = TAG_STYLES[pin.tag];
  const tagLabel = TAG_LABEL[pin.tag];
  if (!tagStyle || !tagLabel) {
    console.warn("[PinCard] Unknown PinTag, falling back to WISH:", pin.tag);
  }
  const resolvedTagStyle = tagStyle ?? TAG_STYLES.WISH;
  const resolvedTagLabel = tagLabel ?? TAG_LABEL.WISH;

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
          className={`inline-flex h-6 shrink-0 items-center rounded-full px-2 text-xs font-medium ${resolvedTagStyle}`}
        >
          {resolvedTagLabel}
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
          {pin.instagramUrl?.startsWith("https://") ? (
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
