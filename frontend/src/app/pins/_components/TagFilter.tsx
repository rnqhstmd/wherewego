"use client";

export type TagFilterValue = "ALL" | "REEL" | "WISH" | "MEMORY";

interface TagFilterProps {
  value: TagFilterValue;
  onChange: (value: TagFilterValue) => void;
  totalCount: number;
  reelCount: number;
  wishCount: number;
  memoryCount: number;
}

const OPTIONS: { value: TagFilterValue; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "REEL", label: "발견" },
  { value: "WISH", label: "위시" },
  { value: "MEMORY", label: "추억" },
];

// Tailwind v4 가 빌드 시점에 정적으로 인식할 수 있도록 클래스 매핑은 동적 템플릿(`bg-pin-${tag}`)
// 대신 Record 로 미리 풀어둔다.
const TAB_CLASSES: Record<TagFilterValue, { active: string; inactive: string }> = {
  ALL: {
    active: "bg-zinc-900 text-white dark:bg-zinc-50 dark:text-zinc-900",
    inactive:
      "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800",
  },
  REEL: {
    active: "bg-pin-reel text-white",
    inactive: "text-pin-reel hover:bg-pin-reel/10",
  },
  WISH: {
    active: "bg-pin-wish text-white",
    inactive: "text-pin-wish hover:bg-pin-wish/10",
  },
  MEMORY: {
    active: "bg-pin-memory text-white",
    inactive: "text-pin-memory hover:bg-pin-memory/10",
  },
};

export function TagFilter({
  value,
  onChange,
  totalCount,
  reelCount,
  wishCount,
  memoryCount,
}: TagFilterProps) {
  const countOf = (option: TagFilterValue): number => {
    if (option === "REEL") return reelCount;
    if (option === "WISH") return wishCount;
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
        const classes = TAB_CLASSES[option.value];
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(option.value)}
            className={`inline-flex h-8 items-center gap-1 rounded-full px-3 text-xs font-medium transition-colors ${
              isActive ? classes.active : classes.inactive
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
