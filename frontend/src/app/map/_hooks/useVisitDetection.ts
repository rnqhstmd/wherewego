"use client";

import { useCallback, useRef } from "react";
import type { PinSummaryResponse } from "@/lib/api/types";
import { haversineKm } from "../_lib/roulette";

/**
 * Phase 10 — 장소 방문 감지 훅.
 *
 * 책임:
 *  - GeolocationPosition + 현재 핀 목록 + 세션 노출 Set 을 받아
 *    "100m 이내 WISH/REEL 핀에 30초 머무름" 조건을 만족하는 핀 1개를 검출.
 *  - 후보 핀별 진입 시각(firstEnterAt) 누적/소거를 내부 ref Map 에 보관.
 *
 * 비책임:
 *  - 토스트/시트 UI 표시, PATCH 호출, 알림 발사 — 호출처(MapClient)가 담당.
 *  - GeolocationPosition 구독 — 외부 GeolocateControl/useGeolocation 결과를 받기만 함.
 *
 * 시계 주입은 별도 인자 없이 `Date.now()` 를 직접 사용한다.
 * 테스트는 `vi.useFakeTimers()` + `vi.setSystemTime(...)` 로 모킹한다 (설계 §5.1, §8.2).
 *
 * 후보 핀 누적 전략 (CONSIDER 반영):
 *  - 100m 이내 모든 WISH/REEL 후보에 대해 firstEnterAt 을 동시에 기록.
 *  - "다음에 올게요" 후 차순위 핀이 이미 30초+ 누적이면 다음 evaluate 에서 즉시 검출.
 *  - 후보 set 에서 벗어난 핀의 firstEnterAt 은 즉시 삭제 (FR-VD-8).
 */

const PROXIMITY_KM = 0.1; // 100m
const PROXIMITY_METERS = 100;
const ACCURACY_MAX_M = 50;
const DWELL_MS = 30_000;
// BBox 사전 필터 (성능): 위도 1도 ≈ 111,320m. 경도는 cos(lat) 가중.
// 100m 반경 BBox 안에 들지 않는 핀은 Haversine 정밀 계산 생략.
const LAT_DEG_PER_METER = 1 / 111_320;
/**
 * Phase 10 보강 (AC-VD-23, 2026-05-24): 걷는 속도 상한 (m/s).
 *
 * <p>차량/자전거 이동 중에는 100m 안에서 신호 대기로 30초+ 정차해도 머무름으로 간주되지 않도록
 * 평가 자체를 건너뛰고 firstEnterAt 을 모두 비운다.
 * 1.4 m/s ≈ 5 km/h. 이 값을 초과하면 이동 중으로 간주한다.
 * iOS Safari 등 {@code position.coords.speed} 가 null/undefined 인 디바이스는 이 가드를
 * 통과(안전 fallback)하여 기존 동작을 유지한다.</p>
 */
const WALKING_SPEED_MAX_MS = 1.4;

interface EvaluateInput {
  position: GeolocationPosition;
  /**
   * 호출자가 미리 WISH/REEL 핀만 필터링하여 전달한다 (성능 — pins prop 변경이 잦으니
   * useMemo 로 캐시 권장). evaluate 내부에서는 tag 검사를 다시 하지 않는다.
   */
  wishReelPins: PinSummaryResponse[];
  shownPinIds: Set<number>;
}

export interface VisitEvaluation {
  detectedPinId: number | null;
}

export interface UseVisitDetectionResult {
  evaluate: (input: EvaluateInput) => VisitEvaluation;
  /**
   * 호출처가 토스트를 닫고 `shownPinIds` 에 해당 pinId 를 추가한 직후
   * 함께 호출하여 내부 firstEnterAt 도 함께 비운다. 동일 핀이 다음 evaluate
   * 호출에서 즉시 후보 set 에서 제외되므로 firstEnterAt 도 잔존시키지 않는다.
   */
  clearFirstEnterAt: (pinId: number) => void;
  /**
   * Phase 10 보강 (AC-VD-24, 2026-05-24): 탭/앱 hidden 동안 firstEnterAt 이 유지되면
   * visible 진입 시 30초 초과 누적으로 즉시 토스트 발동 가능. MapClient 의 visibilitychange
   * 핸들러가 호출하여 firstEnterAt 을 전체 비운다.
   */
  clearAllFirstEnterAt: () => void;
}

export function useVisitDetection(): UseVisitDetectionResult {
  // pinId → 첫 진입 시각(ms). 후보 set 에서 벗어나면 삭제.
  const firstEnterAtRef = useRef<Map<number, number>>(new Map());

  const evaluate = useCallback(
    ({ position, wishReelPins, shownPinIds }: EvaluateInput): VisitEvaluation => {
      // 정확도 미달 — 전체 평가 스킵. firstEnterAt 은 보존하여 다음 정상 콜백에서 누적 활용.
      if (position.coords.accuracy > ACCURACY_MAX_M) {
        return { detectedPinId: null };
      }

      // Phase 10 보강 (AC-VD-23, 2026-05-24): 이동 중(차량/자전거)에는 머무름으로 카운트하지 않는다.
      // 신호 대기로 30초+ 정차해도 firstEnterAt 누적을 막아 오탐을 차단한다.
      // speed 가 null/undefined 인 디바이스(iOS Safari 등)는 통과하여 기존 동작 유지(안전 fallback).
      if (
        position.coords.speed !== null &&
        position.coords.speed !== undefined &&
        position.coords.speed > WALKING_SPEED_MAX_MS
      ) {
        firstEnterAtRef.current.clear();
        return { detectedPinId: null };
      }

      const userLat = position.coords.latitude;
      const userLng = position.coords.longitude;
      const userPos = { lat: userLat, lng: userLng };
      const now = Date.now();

      // BBox 사전 필터 (성능): 사용자 위치 기준 100m 반경의 경위도 BBox 계산.
      // 대부분의 핀은 BBox 밖이라 Haversine 정밀 계산 없이 빠르게 거른다.
      const latDelta = PROXIMITY_METERS * LAT_DEG_PER_METER;
      const cosLat = Math.cos((userLat * Math.PI) / 180);
      // 적도 근접/극단값 안전: cos 가 0 이 되지 않도록 하한.
      const lngDelta = PROXIMITY_METERS * LAT_DEG_PER_METER / Math.max(Math.abs(cosLat), 0.01);

      // 1) 후보 핀 수집 (호출자가 WISH/REEL 만 필터링하여 전달).
      //    shownPinIds 제외 → BBox 사전 필터 → Haversine 정밀 계산.
      const candidates: Array<{ pinId: number; distanceKm: number }> = [];
      for (const pin of wishReelPins) {
        if (shownPinIds.has(pin.id)) continue;
        const pinLat = Number(pin.latitude);
        const pinLng = Number(pin.longitude);
        // BBox 컷 — 대다수 핀이 여기서 거리됨.
        if (Math.abs(pinLat - userLat) > latDelta) continue;
        if (Math.abs(pinLng - userLng) > lngDelta) continue;
        // 정밀 계산.
        const distanceKm = haversineKm(userPos, { lat: pinLat, lng: pinLng });
        if (distanceKm <= PROXIMITY_KM) {
          candidates.push({ pinId: pin.id, distanceKm });
        }
      }

      // 2) 후보에서 벗어난 핀의 firstEnterAt 제거 (FR-VD-8).
      const candidateIds = new Set(candidates.map((c) => c.pinId));
      for (const pinId of firstEnterAtRef.current.keys()) {
        if (!candidateIds.has(pinId)) {
          firstEnterAtRef.current.delete(pinId);
        }
      }

      // 3) 모든 후보에 firstEnterAt 누적 (없는 경우만). 차순위 핀도 함께 추적.
      for (const { pinId } of candidates) {
        if (!firstEnterAtRef.current.has(pinId)) {
          firstEnterAtRef.current.set(pinId, now);
        }
      }

      // 4) 거리 정렬 후 30초+ 누적된 첫 후보를 반환 (가장 가까운 것 우선).
      candidates.sort((a, b) => a.distanceKm - b.distanceKm);
      for (const { pinId } of candidates) {
        const firstEnterAt = firstEnterAtRef.current.get(pinId);
        if (firstEnterAt !== undefined && now - firstEnterAt >= DWELL_MS) {
          return { detectedPinId: pinId };
        }
      }

      return { detectedPinId: null };
    },
    [],
  );

  const clearFirstEnterAt = useCallback((pinId: number) => {
    firstEnterAtRef.current.delete(pinId);
  }, []);

  // Phase 10 보강 (AC-VD-24, 2026-05-24): visibilitychange visible 진입 시 호출.
  const clearAllFirstEnterAt = useCallback(() => {
    firstEnterAtRef.current.clear();
  }, []);

  return { evaluate, clearFirstEnterAt, clearAllFirstEnterAt };
}
