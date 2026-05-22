import { describe, it, expect } from "vitest";
import { geoToApiPixel } from "../geoToPixel";

// 서울시청 좌표를 center로 고정
const CENTER_LNG = 126.9779;
const CENTER_LAT = 37.5663;
const ZOOM = 14;
const API_W = 1024;
const API_H = 1280;

describe("geoToApiPixel", () => {
  it("자기 핀(center와 동일)은 이미지 중앙(apiW/2, apiH/2)으로 변환된다", () => {
    const { x, y } = geoToApiPixel(
      CENTER_LAT, CENTER_LNG,
      CENTER_LAT, CENTER_LNG,
      ZOOM, API_W, API_H,
    );
    expect(x).toBeCloseTo(512, 0);
    expect(y).toBeCloseTo(640, 0);
  });

  it("동쪽 핀(lng 증가)은 x > apiW/2", () => {
    const { x } = geoToApiPixel(
      CENTER_LAT, CENTER_LNG + 0.01,
      CENTER_LAT, CENTER_LNG,
      ZOOM, API_W, API_H,
    );
    expect(x).toBeGreaterThan(512);
  });

  it("북쪽 핀(lat 증가)은 y < apiH/2 — Mercator: 북이 위(y 감소)", () => {
    const { y } = geoToApiPixel(
      CENTER_LAT + 0.01, CENTER_LNG,
      CENTER_LAT, CENTER_LNG,
      ZOOM, API_W, API_H,
    );
    expect(y).toBeLessThan(640);
  });

  it("lat=90 극지방 clamp — NaN/Infinity 없음", () => {
    const { x, y } = geoToApiPixel(
      90, CENTER_LNG,
      CENTER_LAT, CENTER_LNG,
      ZOOM, API_W, API_H,
    );
    expect(Number.isFinite(x)).toBe(true);
    expect(Number.isFinite(y)).toBe(true);
  });

  it("lat=-90 극지방 clamp — NaN/Infinity 없음", () => {
    const { x, y } = geoToApiPixel(
      -90, CENTER_LNG,
      CENTER_LAT, CENTER_LNG,
      ZOOM, API_W, API_H,
    );
    expect(Number.isFinite(x)).toBe(true);
    expect(Number.isFinite(y)).toBe(true);
  });
});
