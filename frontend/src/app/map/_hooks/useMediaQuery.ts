"use client";

import { useCallback, useSyncExternalStore } from "react";

/**
 * 미디어 쿼리 매칭 결과를 반환하는 훅.
 *
 * SSR/하이드레이션 안전성과 React 19의 `react-hooks/set-state-in-effect` 규칙을
 * 동시에 만족시키기 위해 `useSyncExternalStore` 로 외부(matchMedia) 구독을 표현한다.
 * - getServerSnapshot: SSR 시 항상 false (hydration mismatch 방지)
 * - getSnapshot: CSR 시 실제 매칭값
 */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onStoreChange: () => void) => {
      if (typeof window === "undefined") return () => {};
      const mql = window.matchMedia(query);
      mql.addEventListener("change", onStoreChange);
      return () => mql.removeEventListener("change", onStoreChange);
    },
    [query],
  );

  const getSnapshot = useCallback(() => {
    if (typeof window === "undefined") return false;
    return window.matchMedia(query).matches;
  }, [query]);

  const getServerSnapshot = useCallback(() => false, []);

  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}
