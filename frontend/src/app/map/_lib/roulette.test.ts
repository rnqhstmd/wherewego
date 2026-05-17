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
  tag: PinTag = "PLACE",
): PinSummaryResponse {
  return {
    id,
    groupId: 1,
    createdBy: 1,
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
  it("1km에 후보가 있으면 1km에서 픽", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(1, 37.505, 127.005)]; // ~0.7km
    const result = pickRandomWithExpansion(center, pins);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      expect(result.radiusKm).toBe(1);
      expect(result.pin.id).toBe(1);
      expect(result.distanceKm).toBeGreaterThan(0);
      expect(result.distanceKm).toBeLessThan(1);
      expect(result.candidates).toHaveLength(1);
    }
  });

  it("1km 0건, 5km 1건 → 5km에서 픽", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(2, 37.53, 127.03)]; // ~4km
    const result = pickRandomWithExpansion(center, pins);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      expect(result.radiusKm).toBe(5);
      expect(result.pin.id).toBe(2);
    }
  });

  it("모두 0건 → exhausted", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(3, 35.0, 130.0)]; // 매우 멀리
    const result = pickRandomWithExpansion(center, pins);
    expect(result.kind).toBe("exhausted");
  });

  it("MEMORY는 기본 후보(PLACE only)에서 제외", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(4, 37.505, 127.005, "MEMORY")];
    const result = pickRandomWithExpansion(center, pins);
    expect(result.kind).toBe("exhausted");
  });

  it("tagsAllowed에 MEMORY 추가하면 통과", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const pins = [makePin(5, 37.505, 127.005, "MEMORY")];
    const result = pickRandomWithExpansion(center, pins, ["PLACE", "MEMORY"]);
    expect(result.kind).toBe("picked");
  });
});

describe("reRollFromSamePool", () => {
  it("후보 풀에서 무작위 선택, distanceKm 재계산", () => {
    const center = { lat: 37.5, lng: 127.0 };
    const candidates = [makePin(10, 37.503, 127.003)];
    const result = reRollFromSamePool(center, candidates, 1);
    expect(result.kind).toBe("picked");
    if (result.kind === "picked") {
      expect(result.pin.id).toBe(10);
      expect(result.radiusKm).toBe(1);
      expect(result.distanceKm).toBeGreaterThan(0);
    }
  });

  it("빈 풀이면 exhausted", () => {
    const result = reRollFromSamePool({ lat: 37.5, lng: 127.0 }, [], 1);
    expect(result.kind).toBe("exhausted");
  });
});

describe("ROULETTE_RADIUS_STEPS_KM", () => {
  it("순서는 1, 5, 10", () => {
    expect(ROULETTE_RADIUS_STEPS_KM).toEqual([1, 5, 10]);
  });
});
