"use client";

import { useEffect, useRef } from "react";
import { ApiError } from "@/lib/api/http-client";
import { listPinsClient } from "@/lib/api/pin-client";
import type { PinSummaryResponse } from "@/lib/api/types";

interface UseGroupPinSyncOptions {
  groupId: number;
  /** Polling 주기. 기본 30초. */
  intervalMs?: number;
  /**
   * 매 fetch 성공 시 서버 응답 전체를 받는다. 호출자가 단방향 set difference 로
   * 신규 핀만 골라 setState 한다 (append-only 정책).
   */
  onTick: (pins: PinSummaryResponse[]) => void;
  /** 동기화 비활성화. 기본 true. */
  enabled?: boolean;
}

const DEFAULT_INTERVAL_MS = 30_000;
/** 동일 에러 폭주 방지 — 5회 초과 시 콘솔 출력 생략. */
const ERROR_LOG_THROTTLE = 5;

/**
 * 그룹 핀 목록을 주기적으로 polling 하여 onTick 콜백으로 전달한다.
 *
 * <p>정책:</p>
 * <ul>
 *   <li>{@code document.visibilityState === "hidden"} 진입 시 interval 중단 + in-flight abort</li>
 *   <li>{@code visible} 복귀 시 즉시 1회 fetch + interval 재시작</li>
 *   <li>401 응답 시 polling 영구 중단 (페이지 재마운트 전까지)</li>
 *   <li>기타 네트워크 오류는 silent (다음 interval 자연 회복). 동일 에러 5회 초과 시 콘솔 throttle</li>
 *   <li>매 fetch 시작 시 직전 in-flight 요청 abort</li>
 *   <li>unmount / deps 변경 시 모든 리소스 cleanup</li>
 * </ul>
 *
 * <p>onTick 은 ref 로 보관되어 매 호출마다 effect 가 재실행되지 않는다 — 호출자가
 * 매 렌더마다 인라인 함수를 넘겨도 안전.</p>
 */
export function useGroupPinSync({
  groupId,
  intervalMs = DEFAULT_INTERVAL_MS,
  onTick,
  enabled = true,
}: UseGroupPinSyncOptions): void {
  const onTickRef = useRef(onTick);
  useEffect(() => {
    onTickRef.current = onTick;
  }, [onTick]);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return;

    let cancelled = false;
    let intervalId: ReturnType<typeof setInterval> | null = null;
    let controller: AbortController | null = null;
    let stoppedFor401 = false;
    let errorCount = 0;

    const stop = () => {
      if (intervalId !== null) {
        clearInterval(intervalId);
        intervalId = null;
      }
      if (controller) {
        controller.abort();
        controller = null;
      }
    };

    const fetchOnce = async () => {
      if (cancelled || stoppedFor401) return;
      if (controller) controller.abort();
      controller = new AbortController();
      try {
        const res = await listPinsClient(groupId, controller.signal);
        if (cancelled || stoppedFor401) return;
        onTickRef.current(res.items);
        errorCount = 0;
      } catch (e: unknown) {
        if (cancelled) return;
        if (e instanceof DOMException && e.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 401) {
          stoppedFor401 = true;
          stop();
          console.warn(
            "[useGroupPinSync] 401 Unauthorized — polling 중단 (재마운트 시 재개)",
          );
          return;
        }
        errorCount += 1;
        if (errorCount <= ERROR_LOG_THROTTLE) {
          console.warn("[useGroupPinSync] fetch failed", e);
        }
      }
    };

    const start = () => {
      if (intervalId !== null || stoppedFor401) return;
      void fetchOnce();
      intervalId = setInterval(() => {
        void fetchOnce();
      }, intervalMs);
    };

    const handleVisibility = () => {
      if (document.visibilityState === "hidden") {
        stop();
      } else {
        start();
      }
    };

    document.addEventListener("visibilitychange", handleVisibility);
    if (document.visibilityState !== "hidden") {
      start();
    }

    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", handleVisibility);
      stop();
    };
  }, [groupId, intervalMs, enabled]);
}
