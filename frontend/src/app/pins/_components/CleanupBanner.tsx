"use client";

import { useEffect, useState, useTransition } from "react";

import {
  executeCleanup,
  fetchCleanupCandidates,
} from "@/lib/api/cleanup-client";
import { ApiError } from "@/lib/api/http-client";
import { snoozeCleanup } from "@/lib/api/me-client";
import type { CleanupCandidatesResponse } from "@/lib/api/types";

interface CleanupBannerProps {
  groupId: number;
  /** 정리 실행 성공 시 호출 — 부모가 목록을 갱신한다. */
  onCleanupComplete?: () => void;
}

/**
 * Phase 12 (FR-PIN-12-23~25): 오래된 발견 핀 정리 배너.
 *
 * <p>마운트 시 후보를 1회 조회하여 totalCount &gt; 0 일 때만 노출한다.
 * - [한꺼번에 정리]: 일괄 soft-delete 후 부모에게 갱신 콜백.
 * - [나중에]: 7일 snooze 후 배너 숨김.</p>
 */
export function CleanupBanner({
  groupId,
  onCleanupComplete,
}: CleanupBannerProps) {
  const [candidates, setCandidates] =
    useState<CleanupCandidatesResponse | null>(null);
  const [hidden, setHidden] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    const controller = new AbortController();
    fetchCleanupCandidates(groupId, controller.signal)
      .then((data) => {
        setCandidates(data);
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        // 조회 실패는 배너 미노출로 graceful degrade.
        console.warn("[CleanupBanner] fetchCleanupCandidates failed", error);
      });
    return () => controller.abort();
  }, [groupId]);

  if (hidden) return null;
  if (!candidates) return null;
  if (candidates.totalCount === 0) return null;

  const handleExecute = () => {
    setErrorMessage(null);
    startTransition(async () => {
      try {
        await executeCleanup(groupId);
        setHidden(true);
        onCleanupComplete?.();
      } catch (error) {
        const message =
          error instanceof ApiError
            ? error.message
            : "정리 중 오류가 발생했어요. 잠시 후 다시 시도해주세요.";
        setErrorMessage(message);
      }
    });
  };

  const handleSnooze = () => {
    setErrorMessage(null);
    startTransition(async () => {
      try {
        await snoozeCleanup();
        setHidden(true);
      } catch (error) {
        const message =
          error instanceof ApiError
            ? error.message
            : "잠시 후 다시 시도해주세요.";
        setErrorMessage(message);
      }
    });
  };

  return (
    <section
      aria-label="오래된 발견 핀 정리"
      className="mb-4 flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 dark:border-amber-900/60 dark:bg-amber-950/30"
    >
      <p className="text-sm font-medium text-amber-900 dark:text-amber-200">
        🗑️ 30일째 관심받지 못한 발견 핀이 {candidates.totalCount}개 있어요
      </p>
      {errorMessage ? (
        <p
          role="alert"
          className="text-xs text-red-700 dark:text-red-300"
        >
          {errorMessage}
        </p>
      ) : null}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={handleExecute}
          disabled={isPending}
          className="inline-flex h-8 items-center rounded-full bg-amber-600 px-3 text-xs font-medium text-white transition-colors hover:bg-amber-700 disabled:opacity-50 dark:bg-amber-500 dark:hover:bg-amber-600"
        >
          🧹 한꺼번에 정리
        </button>
        <button
          type="button"
          onClick={handleSnooze}
          disabled={isPending}
          className="inline-flex h-8 items-center rounded-full border border-amber-300 bg-white px-3 text-xs font-medium text-amber-800 transition-colors hover:bg-amber-100 disabled:opacity-50 dark:border-amber-800 dark:bg-zinc-900 dark:text-amber-200 dark:hover:bg-amber-950/40"
        >
          ⏰ 나중에
        </button>
      </div>
    </section>
  );
}
