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

/**
 * Permissions API 가 노출하는 권한 상태. 좌표 fetch와는 독립적.
 * - unknown: Permissions API 미지원/조회 실패.
 */
export type PermissionState = "granted" | "denied" | "prompt" | "unknown";

interface UseGeolocationResult {
  state: GeoState;
  /**
   * 사전 권한 조회 결과. 좌표를 제공하지는 않으나,
   * granted/denied 가 이미 결정된 경우 셔플 UI 분기에 활용한다.
   */
  permissionState: PermissionState;
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
  const [permissionState, setPermissionState] =
    useState<PermissionState>("unknown");

  // 사전 권한 조회: denied/granted 모두 추적해 UI 분기에 사용.
  // granted여도 좌표는 request() 호출 시점에 fetch (Permissions API 는 좌표 제공 안 함).
  useEffect(() => {
    if (typeof navigator === "undefined" || !navigator.permissions) return;
    let cancelled = false;
    let statusRef: PermissionStatus | null = null;
    const handleChange = () => {
      if (cancelled || !statusRef) return;
      setPermissionState(statusRef.state as PermissionState);
      if (statusRef.state === "denied") {
        setState({ status: "denied" });
      }
    };
    navigator.permissions
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .query({ name: "geolocation" as any })
      .then((result) => {
        if (cancelled) return;
        statusRef = result;
        setPermissionState(result.state as PermissionState);
        if (result.state === "denied") {
          setState({ status: "denied" });
        }
        result.addEventListener?.("change", handleChange);
      })
      .catch(() => {
        // 미지원 브라우저는 unknown 유지.
      });
    return () => {
      cancelled = true;
      statusRef?.removeEventListener?.("change", handleChange);
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

  return { state, permissionState, request, reset };
}
