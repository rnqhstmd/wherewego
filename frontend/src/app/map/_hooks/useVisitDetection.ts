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
const ACCURACY_MAX_M = 50;
const DWELL_MS = 30_000;

interface EvaluateInput {
  position: GeolocationPosition;
  pins: PinSummaryResponse[];
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
}

export function useVisitDetection(): UseVisitDetectionResult {
  // pinId → 첫 진입 시각(ms). 후보 set 에서 벗어나면 삭제.
  const firstEnterAtRef = useRef<Map<number, number>>(new Map());

  const evaluate = useCallback(
    ({ position, pins, shownPinIds }: EvaluateInput): VisitEvaluation => {
      // 정확도 미달 — 전체 평가 스킵. firstEnterAt 은 보존하여 다음 정상 콜백에서 누적 활용.
      if (position.coords.accuracy > ACCURACY_MAX_M) {
        return { detectedPinId: null };
      }

      const userPos = {
        lat: position.coords.latitude,
        lng: position.coords.longitude,
      };
      const now = Date.now();

      // 1) 후보 핀 수집: WISH/REEL + shownPinIds 제외 + 100m 이내.
      const candidates: Array<{ pinId: number; distanceKm: number }> = [];
      for (const pin of pins) {
        if (pin.tag !== "WISH" && pin.tag !== "REEL") continue;
        if (shownPinIds.has(pin.id)) continue;
        const distanceKm = haversineKm(userPos, {
          lat: Number(pin.latitude),
          lng: Number(pin.longitude),
        });
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

  return { evaluate, clearFirstEnterAt };
}
