// 위치 기반 룰렛 계산 로직 (설계 §10).
//
// - Haversine 거리(km) + bbox 1차 필터 + 정확 원 필터.
// - 1km → 5km → 10km 자동 확장 후보 추첨.
// - "다시" 동작은 마지막 성공 풀에서 재추첨 (같은 radius, 같은 후보 set).

import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

export interface LatLng {
  lat: number;
  lng: number;
}

export const ROULETTE_RADIUS_STEPS_KM = [10] as const;
export type RouletteRadiusKm = (typeof ROULETTE_RADIUS_STEPS_KM)[number];

/**
 * 두 좌표 사이 거리 (km, Haversine 공식, 지구 반경 6371km).
 */
export function haversineKm(a: LatLng, b: LatLng): number {
  const R = 6371;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const sinDLat = Math.sin(dLat / 2);
  const sinDLng = Math.sin(dLng / 2);
  const h =
    sinDLat * sinDLat +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * sinDLng * sinDLng;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/**
 * Bounding Box 1차 필터.
 *
 * 1km ≈ 위도 0.009도(=1/111). 경도는 위도에 따라 cos 보정.
 * 정확한 원 필터(withinRadius)의 비용을 줄이기 위한 사전 컷.
 */
export function bboxFilter(
  center: LatLng,
  pins: PinSummaryResponse[],
  radiusKm: number,
): PinSummaryResponse[] {
  const latDelta = radiusKm / 111;
  const cosLat = Math.cos((center.lat * Math.PI) / 180);
  const lngDelta = radiusKm / (111 * Math.max(0.0001, cosLat));
  const minLat = center.lat - latDelta;
  const maxLat = center.lat + latDelta;
  const minLng = center.lng - lngDelta;
  const maxLng = center.lng + lngDelta;
  return pins.filter((p) => {
    const lat = Number(p.latitude);
    const lng = Number(p.longitude);
    return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
  });
}

/**
 * Haversine 기반 정확 반경 필터. bbox 1차 필터 → Haversine 검증.
 */
export function withinRadius(
  center: LatLng,
  pins: PinSummaryResponse[],
  radiusKm: number,
): PinSummaryResponse[] {
  return bboxFilter(center, pins, radiusKm).filter(
    (p) =>
      haversineKm(center, {
        lat: Number(p.latitude),
        lng: Number(p.longitude),
      }) <= radiusKm,
  );
}

export type RouletteOutcome =
  | {
      kind: "picked";
      pin: PinSummaryResponse;
      radiusKm: RouletteRadiusKm;
      candidates: PinSummaryResponse[];
      candidateCount: number;
      distanceKm: number;
    }
  | { kind: "exhausted" };

/**
 * 자동 확장 추첨 (1km → 5km → 10km, 설계 §10 FR-REC-5).
 *
 * 1) tagsAllowed로 후보 풀 필터 (기본 ["PLACE"])
 * 2) 가장 좁은 반경부터 시도, 후보가 1건이라도 있으면 그 반경에서 무작위 선택
 * 3) 10km까지 모두 0건이면 exhausted
 */
export function pickRandomWithExpansion(
  center: LatLng,
  pins: PinSummaryResponse[],
  tagsAllowed: PinTag[] = ["PLACE"],
): RouletteOutcome {
  const eligible = pins.filter((p) => tagsAllowed.includes(p.tag));
  for (const radiusKm of ROULETTE_RADIUS_STEPS_KM) {
    const candidates = withinRadius(center, eligible, radiusKm);
    if (candidates.length > 0) {
      const picked = candidates[Math.floor(Math.random() * candidates.length)];
      const distanceKm = haversineKm(center, {
        lat: Number(picked.latitude),
        lng: Number(picked.longitude),
      });
      return {
        kind: "picked",
        pin: picked,
        radiusKm,
        candidates,
        candidateCount: candidates.length,
        distanceKm,
      };
    }
  }
  return { kind: "exhausted" };
}

/**
 * "다시" 동작 (FR-REC-6).
 *
 * 마지막 성공한 후보 풀에서 같은 radius로 무작위 재선택.
 * prevPinId가 주어지고 후보가 2개 이상이면 직전 핀을 제외하여 같은 곳만 반복되는 것을 방지.
 * 후보가 1개면 그대로 같은 핀 반환 (선택지 없음).
 */
export function reRollFromSamePool(
  center: LatLng,
  candidates: PinSummaryResponse[],
  radiusKm: RouletteRadiusKm,
  prevPinId?: number,
): RouletteOutcome {
  if (candidates.length === 0) return { kind: "exhausted" };
  const pool =
    prevPinId !== undefined && candidates.length > 1
      ? candidates.filter((p) => p.id !== prevPinId)
      : candidates;
  const picked = pool[Math.floor(Math.random() * pool.length)];
  const distanceKm = haversineKm(center, {
    lat: Number(picked.latitude),
    lng: Number(picked.longitude),
  });
  return {
    kind: "picked",
    pin: picked,
    radiusKm,
    candidates,
    candidateCount: candidates.length,
    distanceKm,
  };
}
