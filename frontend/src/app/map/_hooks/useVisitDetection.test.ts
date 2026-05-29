import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useVisitDetection } from "./useVisitDetection";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

/**
 * 기준 시각: 2026-05-23T10:00:00Z. vi.setSystemTime 으로 고정 후
 * advanceTimersByTime 으로 Date.now() 를 진행시킨다.
 */
const BASE_TIME = new Date("2026-05-23T10:00:00Z");

function makePin(overrides: Partial<PinSummaryResponse> = {}): PinSummaryResponse {
  return {
    id: 1,
    groupId: 1,
    createdBy: 1,
    createdByNickname: null,
    placeName: "테스트 장소",
    address: null,
    latitude: 37.5,
    longitude: 127.0,
    instagramUrl: null,
    memo: null,
    memoSource: null,
    tag: "WISH" as PinTag,
    createdAt: "2026-05-23T00:00:00Z",
    visitedAt: null,
    memoUpdatedBy: null,
    memoUpdatedByNickname: null,
    ...overrides,
  };
}

/**
 * 가짜 GeolocationPosition. 실제 브라우저 객체와 동일한 shape 만 갖춘다.
 * coords 만 사용하므로 필수 필드만 채운다.
 */
function makePosition({
  lat,
  lng,
  accuracy = 20,
  speed = null,
}: {
  lat: number;
  lng: number;
  accuracy?: number;
  /**
   * Phase 10 보강 (AC-VD-23, 2026-05-24): m/s 단위 속도. {@code null} 이 기본값으로,
   * iOS Safari 등 speed 미보고 디바이스의 안전 fallback 케이스를 시뮬레이션한다.
   */
  speed?: number | null;
}): GeolocationPosition {
  return {
    coords: {
      latitude: lat,
      longitude: lng,
      accuracy,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed,
      toJSON() {
        return {};
      },
    },
    timestamp: Date.now(),
    toJSON() {
      return {};
    },
  } as unknown as GeolocationPosition;
}

describe("useVisitDetection", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(BASE_TIME);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("accuracy > 100m 이면 평가 전체를 스킵한다", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });

    let evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, accuracy: 130 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();

    // 30초가 지나도 정확도 부족이면 firstEnterAt 자체가 기록되지 않아 검출 0.
    act(() => {
      vi.advanceTimersByTime(31_000);
    });
    evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, accuracy: 150 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();
  });

  it("첫 진입 시 firstEnterAt 만 기록하고 즉시 검출하지 않는다", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });
    const evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();
  });

  it("진입 후 30초가 경과하면 해당 핀을 검출한다 (경계값 30000ms 포함)", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });

    // t=0: 첫 진입
    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });

    // t=29999ms: 아직 검출 안 됨
    act(() => {
      vi.advanceTimersByTime(29_999);
    });
    let evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();

    // t=30000ms: 검출 (경계 포함)
    act(() => {
      vi.advanceTimersByTime(1);
    });
    evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBe(1);
  });

  it("25초 후 100m 밖으로 이탈하면 firstEnterAt 이 사라지고, 재진입 시 카운터가 0부터 다시 시작한다", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });

    // t=0 진입
    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });

    // t=25s: 후보 밖으로 이탈 → firstEnterAt 제거
    act(() => {
      vi.advanceTimersByTime(25_000);
    });
    result.current.evaluate({
      position: makePosition({ lat: 38.0, lng: 128.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });

    // t=26s: 재진입 (이전 25초 누적 무효 — 새 firstEnterAt)
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    let evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();

    // 재진입 +29초: 아직 검출 안 됨
    act(() => {
      vi.advanceTimersByTime(29_000);
    });
    evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();

    // 재진입 +30초: 검출
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBe(1);
  });

  // Phase 10 성능 (2026-05-24): MEMORY 핀 제외는 호출자(MapClient.wishReelPins useMemo)의
  // 책임으로 분리됨. evaluate 인터페이스 자체가 wishReelPins 만 받으므로 evaluate 단위 테스트
  // 에서는 MEMORY 제외를 검증하지 않는다. 대신 BBox 사전 필터 동작을 검증한다.
  it("BBox 사전 필터 — 100m 명백히 초과 핀은 detect 되지 않는다", () => {
    const { result } = renderHook(() => useVisitDetection());
    // 위도 0.002도 ≈ 222m 차이 → BBox 밖 → Haversine 호출 없이 거름.
    const farPin = makePin({ id: 1, latitude: 37.502, longitude: 127.0 });

    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [farPin],
      shownPinIds: new Set(),
    });
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    const evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [farPin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();
  });

  it("shownPinIds 에 포함된 핀은 후보에서 제외된다", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });

    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set([1]),
    });
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    const evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set([1]),
    });
    expect(evaluation.detectedPinId).toBeNull();
  });

  it("차순위 후보 핀도 firstEnterAt 이 누적되어, 1순위가 shownPinIds 에 들어가면 즉시 차순위가 검출된다", () => {
    const { result } = renderHook(() => useVisitDetection());
    // 두 핀이 모두 100m 이내. 첫 핀이 더 가깝다.
    const nearer = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });
    const farther = makePin({
      id: 2,
      latitude: 37.5005, // 약 55m 떨어진 위치
      longitude: 127.0,
    });
    const pins = [nearer, farther];
    const shown = new Set<number>();

    // t=0: 두 핀 모두 firstEnterAt 기록
    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: pins,
      shownPinIds: shown,
    });

    // t=30s: 1순위(nearer) 검출
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    let evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: pins,
      shownPinIds: shown,
    });
    expect(evaluation.detectedPinId).toBe(1);

    // 사용자가 "다음에 올게요" — 1순위를 shown 에 추가하고 clearFirstEnterAt 호출.
    shown.add(1);
    result.current.clearFirstEnterAt(1);

    // 같은 콜백(시간 진행 없음)에서 즉시 차순위(farther) 검출 — 차순위도 이미 30초+ 누적.
    evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0 }),
      wishReelPins: pins,
      shownPinIds: shown,
    });
    expect(evaluation.detectedPinId).toBe(2);
  });

  // Phase 10 보강 (AC-VD-23, 2026-05-24): speed 게이트 — 이동 중 오탐 차단.
  it("speed > 1.4 m/s (이동 중) 이면 평가가 스킵되고 기존 firstEnterAt 도 모두 비워진다", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });

    // 사전 누적: speed=null (정상 안전 fallback) 케이스로 firstEnterAt 기록.
    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, speed: null }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });

    // 30초 경과 — 본래라면 검출되어야 함.
    act(() => {
      vi.advanceTimersByTime(30_000);
    });

    // 그러나 이번 콜백에서 speed=2.0 m/s (≈7.2 km/h, 가벼운 달리기/자전거)이므로
    // 평가가 스킵되고 firstEnterAt 도 비워져 검출되지 않는다.
    let evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, speed: 2.0 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();

    // 다음 콜백에서 다시 정상 속도(speed=null)로 진입해도 firstEnterAt 이 비워져 있어
    // 즉시 검출되지 않는다 (재진입 시 30초부터 다시 측정).
    evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, speed: null }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBeNull();
  });

  it("speed = null (iOS Safari 등 미보고 디바이스) 이면 정상 평가가 진행된다 (안전 fallback)", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });

    // t=0: 첫 진입
    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, speed: null }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });

    // t=30s: 정상 검출
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    const evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, speed: null }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBe(1);
  });

  it("speed = 0.5 m/s (걷기 미만) 이면 정상 평가가 진행된다", () => {
    const { result } = renderHook(() => useVisitDetection());
    const pin = makePin({ id: 1, latitude: 37.5, longitude: 127.0 });

    // t=0: 첫 진입 (천천히 걷는 속도)
    result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, speed: 0.5 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });

    // t=30s: 정상 검출 — 1.4 m/s 미만이면 머무름 측정 정상 진행.
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    const evaluation = result.current.evaluate({
      position: makePosition({ lat: 37.5, lng: 127.0, speed: 0.5 }),
      wishReelPins: [pin],
      shownPinIds: new Set(),
    });
    expect(evaluation.detectedPinId).toBe(1);
  });
});
