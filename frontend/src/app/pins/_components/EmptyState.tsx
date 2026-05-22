import type { TagFilterValue } from "./TagFilter";

interface EmptyStateProps {
  filter: TagFilterValue;
  hasPins: boolean;
}

const FILTER_LABEL: Record<Exclude<TagFilterValue, "ALL">, string> = {
  REEL: "발견",
  WISH: "위시",
  MEMORY: "추억",
};

export function EmptyState({ filter, hasPins }: EmptyStateProps) {
  const message =
    hasPins && filter !== "ALL"
      ? `${FILTER_LABEL[filter]} 태그의 핀이 없습니다.`
      : "아직 등록된 핀이 없습니다.";

  const subMessage = hasPins
    ? "다른 태그를 선택해 보세요."
    : "챗봇으로 Instagram 링크를 보내면 핀이 자동으로 등록됩니다.";

  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-zinc-200 bg-white px-6 py-16 text-center dark:border-zinc-800 dark:bg-zinc-900">
      <p className="text-base font-medium text-zinc-700 dark:text-zinc-300">
        {message}
      </p>
      <p className="text-sm text-zinc-500 dark:text-zinc-400">{subMessage}</p>
    </div>
  );
}
