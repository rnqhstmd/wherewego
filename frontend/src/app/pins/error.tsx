"use client";

import { useEffect } from "react";

interface PinsErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function PinsError({ error, reset }: PinsErrorProps) {
  useEffect(() => {
    console.error("[pins] route error:", error.digest ?? error.message);
  }, [error]);

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col items-start gap-4 px-4 py-10">
      <h2 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
        핀 목록을 불러오지 못했습니다
      </h2>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        잠시 후 다시 시도해 주세요. 문제가 계속되면 관리자에게 문의해 주세요.
      </p>
      <button
        type="button"
        onClick={() => reset()}
        className="inline-flex h-10 items-center justify-center rounded-full bg-zinc-900 px-5 text-sm font-medium text-white transition-colors hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
      >
        다시 시도
      </button>
    </div>
  );
}
