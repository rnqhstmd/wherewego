"use client";

import { useCallback, useEffect, useState } from "react";

export interface LatLng {
  lat: number;
  lng: number;
}

/**
 * Geolocation API 상태 머신 (설계 §11).
 *
 * - idle: 미요청
 * - prompting: 사용자에게 브라우저 권한 다이얼로그 노출 중 (또는 좌표 fetch 대기)
 * - granted: 허용 + 좌표 확보
 * - denied / unavailable / timeout: 각 실패 분기
 */
export type GeoState =
  | { status: "idle" }
  | { status: "prompting" }
  | { status: "granted"; coords: LatLng }
  | { status: "denied" }
  | { status: "unavailable" }
  | { status: "timeout" };

interface UseGeolocationResult {
  state: GeoState;
  /** 좌표를 새로 요청 (granted여도 fresh 좌표를 다시 받음) */
  request: () => void;
  /** idle로 초기화 (시트 닫을 때 등) */
  reset: () => void;
}

/**
 * 위치 권한 + 좌표 fetch 훅.
 *
 * SSR 가드: 첫 effect에서 `typeof navigator !== 'undefined'` 검사.
 * 캐시 미사용 (매번 fresh 좌표).
 */
export function useGeolocation(): UseGeolocationResult {
  const [state, setState] = useState<GeoState>({ status: "idle" });

  // 사전 권한 조회: 이미 denied인 사용자는 시작부터 denied로.
  // granted여도 좌표는 request() 호출 시점에 fetch.
  useEffect(() => {
    if (typeof navigator === "undefined" || !navigator.permissions) return;
    let cancelled = false;
    navigator.permissions
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .query({ name: "geolocation" as any })
      .then((result) => {
        if (cancelled) return;
        if (result.state === "denied") {
          setState({ status: "denied" });
        }
      })
      .catch(() => {
        // 미지원 브라우저는 idle 유지.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const request = useCallback(() => {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setState({ status: "unavailable" });
      return;
    }
    setState({ status: "prompting" });
    navigator.geolocation.getCurrentPosition(
      (pos) =>
        setState({
          status: "granted",
          coords: { lat: pos.coords.latitude, lng: pos.coords.longitude },
        }),
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          setState({ status: "denied" });
        } else if (err.code === err.TIMEOUT) {
          setState({ status: "timeout" });
        } else {
          setState({ status: "unavailable" });
        }
      },
      { timeout: 8000, enableHighAccuracy: false },
    );
  }, []);

  const reset = useCallback(() => setState({ status: "idle" }), []);

  return { state, request, reset };
}
