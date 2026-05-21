import { describe, it, expect } from "vitest";
import {
  haversineKm,
  bboxFilter,
  withinRadius,
  pickRandomWithExpansion,
  reRollFromSamePool,
  ROULETTE_RADIUS_STEPS_KM,
} from "./roulette";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

function makePin(
  id: number,
  lat: number,
  lng: number,
  tag: PinTag = "REEL",
): PinSummaryResponse {
  return {
    id,
    groupId: 1,
    createdBy: 1,
    createdByNickname: null,
    placeName: `pin-${id}`,
    address: null,
    latitude: lat,
    longitude: lng,
    instagramUrl: null,
    memo: null,
    memoSource: null,
    tag,
    createdAt: "2025-01-01T00:00:00Z",
  };
}

describe("haversineKm", () => {
  it("서울 시청 ↔ 강남역 ≈ 7~9km", () => {
    const seoulCityHall = { lat: 37.5665, lng: 126.978 };
    const gangnam = { lat: 37.4979, lng: 127.0276 };
    const d = haversineKm(seoulCityHall, gangnam);
    expect(d).toBeGreaterThan(7.0);
    expect(d).toBeLessThan(9.0);
  });

  it("동일 좌표는 0km", () => {
    const p = { lat: 37.5, lng: 127.0 };
    expect(haversineKm(p, p)).toBeCloseTo(0, 5);
  });
});

describe("bboxFilter", () => {
  it("1km 박스 내 핀만 통과", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [
      makePin(1, 37.505, 127.005), // ~0.7km
      makePin(2, 37.52, 127.02), // ~3km
      makePin(3, 37.5, 127.0), // 0km
    ];
    const out = bboxFilter(center, pins, 1);
    expect(out.map((p) => p.id).sort()).toEqual([1, 3]);
  });
});

describe("withinRadius", () => {
  it("bbox 모서리에 있어도 원 외부면 제외", () => {
    const center = { lat: 37.5, lng: 127.0 };
    // bbox 모서리(대각선 ~1.4km)에 가까운 핀
    const cornerPin = makePin(
      99,
      37.5 + (1 / 111) * 0.99,
      127.0 + (1 / 111) * 0.99,
    );
    const within = withinRadius(center, [cornerPin], 1);
    expect(within).toEqual([]);
  });

  it("원 내부 핀은 통과", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const insidePin = makePin(1, 37.503, 127.003); // ~0.4km
    const within = withinRadius(center, [insidePin], 1);
    expect(within).toHaveLength(1);
  });
});

describe("pickRandomWithExpansion", () => {
  it("후보가 있으면 10km 반경에서 픽", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(1, 37.505, 127.005)]; // ~0.7km, REEL
    const result = pickRandomWithExpansion(center, pins);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      expect(result.radiusKm).toBe(10);
      expect(result.pin.id).toBe(1);
      expect(result.distanceKm).toBeGreaterThan(0);
      expect(result.distanceKm).toBeLessThan(1);
      expect(result.candidates).toHaveLength(1);
    }
  });

  it("모두 반경 밖이면 exhausted", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(3, 35.0, 130.0)]; // 매우 멀리
    const result = pickRandomWithExpansion(center, pins);
    expect(result.kind).toBe("exhausted");
  });

  // (AC-6) 기본 풀(REEL+WISH)에서 REEL/WISH 핀은 후보, MEMORY 핀은 제외.
  it("(AC-6) 기본 풀은 REEL+WISH만 포함, MEMORY는 제외", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const reelPin = makePin(1, 37.501, 127.001, "REEL");
    const wishPin = makePin(2, 37.502, 127.002, "WISH");
    const memoryPin = makePin(3, 37.503, 127.003, "MEMORY");
    const result = pickRandomWithExpansion(center, [reelPin, wishPin, memoryPin]);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      const ids = result.candidates.map((p) => p.id).sort();
      expect(ids).toEqual([1, 2]);
      expect(result.candidates.find((p) => p.tag === "MEMORY")).toBeUndefined();
    }
  });

  // (FR-7-8) WISH 단독 핀도 기본 풀에서 통과.
  it("(FR-7-8) WISH 단독 핀도 기본 풀 통과", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(10, 37.505, 127.005, "WISH")];
    const result = pickRandomWithExpansion(center, pins);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      expect(result.pin.id).toBe(10);
      expect(result.pin.tag).toBe("WISH");
    }
  });

  // (AC-7) tagsAllowed에 MEMORY 추가하면 MEMORY 핀도 후보 통과.
  it("(AC-7) tagsAllowed=['REEL','WISH','MEMORY']이면 MEMORY 핀도 통과", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const memoryPin = makePin(5, 37.505, 127.005, "MEMORY");
    const result = pickRandomWithExpansion(center, [memoryPin], [
      "REEL",
      "WISH",
      "MEMORY",
    ]);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      expect(result.pin.tag).toBe("MEMORY");
    }
  });

  // (AC-8) 허용 태그 범위에서 매칭 0건이면 exhausted.
  it("(AC-8) tagsAllowed 범위에서 0건이면 exhausted", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const memoryPin = makePin(6, 37.505, 127.005, "MEMORY");
    // 기본 풀은 REEL+WISH 이므로 MEMORY 단독은 통과 후보 0건.
    const result = pickRandomWithExpansion(center, [memoryPin]);
    expect(result.kind).toBe("exhausted");
  });
});

describe("reRollFromSamePool", () => {
  it("후보 풀에서 무작위 선택, distanceKm 재계산", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const candidates = [makePin(10, 37.503, 127.003)];
    const result = reRollFromSamePool(center, candidates, 10);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      expect(result.pin.id).toBe(10);
      expect(result.radiusKm).toBe(10);
      expect(result.distanceKm).toBeGreaterThan(0);
    }
  });

  it("빈 풀이면 exhausted", () => {
    const result = reRollFromSamePool({ lat: 37.5, lng: 127.0 }, [], 10);
    expect(result.kind).toBe("exhausted");
  });
});

describe("ROULETTE_RADIUS_STEPS_KM", () => {
  it("10km 단일 반경", () => {
    expect(ROULETTE_RADIUS_STEPS_KM).toEqual([10]);
  });
});
