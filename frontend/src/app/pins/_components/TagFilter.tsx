"use client";

export type TagFilterValue = "ALL" | "PLACE" | "MEMORY";

interface TagFilterProps {
  value: TagFilterValue;
  onChange: (value: TagFilterValue) => void;
  totalCount: number;
  placeCount: number;
  memoryCount: number;
}

const OPTIONS: { value: TagFilterValue; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "PLACE", label: "장소" },
  { value: "MEMORY", label: "추억" },
];

export function TagFilter({
  value,
  onChange,
  totalCount,
  placeCount,
  memoryCount,
}: TagFilterProps) {
  const countOf = (option: TagFilterValue): number => {
    if (option === "PLACE") return placeCount;
    if (option === "MEMORY") return memoryCount;
    return totalCount;
  };

  return (
    <div
      role="tablist"
      aria-label="태그 필터"
      className="inline-flex items-center gap-1 rounded-full border border-zinc-200 bg-white p-1 dark:border-zinc-800 dark:bg-zinc-900"
    >
      {OPTIONS.map((option) => {
        const isActive = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(option.value)}
            className={`inline-flex h-8 items-center gap-1 rounded-full px-3 text-xs font-medium transition-colors ${
              isActive
                ? "bg-zinc-900 text-white dark:bg-zinc-50 dark:text-zinc-900"
                : "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800"
            }`}
          >
            <span>{option.label}</span>
            <span
              className={`inline-flex h-5 min-w-5 items-center justify-center rounded-full px-1 text-[10px] ${
                isActive
                  ? "bg-white/20 text-white dark:bg-zinc-900/20 dark:text-zinc-900"
                  : "bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400"
              }`}
            >
              {countOf(option.value)}
            </span>
          </button>
        );
      })}
    </div>
  );
}
