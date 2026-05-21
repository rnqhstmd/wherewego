import { describe, it, expect } from "vitest";
import {
  buildMapboxStaticUrl,
  extractStyleId,
} from "../mapboxStaticUrl";

describe("extractStyleId (Phase 9)", () => {
  it("mapbox://styles/{user}/{styleId} 형태에서 user/styleId 추출", () => {
    expect(extractStyleId("mapbox://styles/myuser/mystyle")).toBe(
      "myuser/mystyle",
    );
  });

  it("기본 Mapbox 스타일 URL도 동일하게 추출한다", () => {
    expect(extractStyleId("mapbox://styles/mapbox/streets-v12")).toBe(
      "mapbox/streets-v12",
    );
  });

  it("null 입력은 mapbox/streets-v12 폴백", () => {
    expect(extractStyleId(null)).toBe("mapbox/streets-v12");
  });

  it("undefined 입력은 mapbox/streets-v12 폴백", () => {
    expect(extractStyleId(undefined)).toBe("mapbox/streets-v12");
  });

  it("빈 문자열은 폴백", () => {
    expect(extractStyleId("")).toBe("mapbox/streets-v12");
  });

  it("https 같은 잘못된 스킴은 폴백", () => {
    expect(extractStyleId("https://example.com/foo/bar")).toBe(
      "mapbox/streets-v12",
    );
  });

  it("mapbox://styles/ 뒤 슬래시 누락은 폴백", () => {
    expect(extractStyleId("mapbox://styles/onlyuser")).toBe(
      "mapbox/streets-v12",
    );
  });
});

describe("buildMapboxStaticUrl (Phase 9)", () => {
  it("정상 파라미터로 Static API URL을 빌드한다 (서울 좌표 + 기본 스타일)", () => {
    const url = buildMapboxStaticUrl({
      lat: 37.5665,
      lng: 126.978,
      width: 1024,
      height: 1280,
      zoom: 14,
      token: "pk.testtoken",
    });
    expect(url).toBe(
      "https://api.mapbox.com/styles/v1/mapbox/streets-v12/static/126.978000,37.566500,14,0/1024x1280?access_token=pk.testtoken",
    );
  });

  it("styleId가 명시되면 그대로 사용한다", () => {
    const url = buildMapboxStaticUrl({
      lat: 37.5,
      lng: 127.0,
      width: 500,
      height: 500,
      zoom: 12,
      token: "tk",
      styleId: "myuser/mystyle",
    });
    expect(url).toContain("/styles/v1/myuser/mystyle/static/");
  });

  it("styleId가 null이면 기본 스타일로 폴백한다", () => {
    const url = buildMapboxStaticUrl({
      lat: 0,
      lng: 0,
      width: 100,
      height: 100,
      zoom: 1,
      token: "tk",
      styleId: null,
    });
    expect(url).toContain("/styles/v1/mapbox/streets-v12/static/");
  });

  it("styleId가 undefined이면 기본 스타일로 폴백한다", () => {
    const url = buildMapboxStaticUrl({
      lat: 0,
      lng: 0,
      width: 100,
      height: 100,
      zoom: 1,
      token: "tk",
    });
    expect(url).toContain("/styles/v1/mapbox/streets-v12/static/");
  });

  it("좌표는 toFixed(6) 으로 자릿수가 안정화된다", () => {
    const url = buildMapboxStaticUrl({
      lat: 37.123456789,
      lng: 126.987654321,
      width: 100,
      height: 100,
      zoom: 10,
      token: "tk",
    });
    expect(url).toContain("/126.987654,37.123457,10,0/");
  });

  it("width/height 파라미터가 URL에 반영된다 (1024x1280)", () => {
    const url = buildMapboxStaticUrl({
      lat: 37,
      lng: 127,
      width: 1024,
      height: 1280,
      zoom: 14,
      token: "tk",
    });
    expect(url).toContain("/1024x1280?");
  });

  it("token은 URL 인코딩된다", () => {
    const url = buildMapboxStaticUrl({
      lat: 37,
      lng: 127,
      width: 100,
      height: 100,
      zoom: 10,
      token: "pk.with space&special",
    });
    expect(url).toContain("access_token=pk.with%20space%26special");
  });

  it("zoom 값이 그대로 URL에 들어간다", () => {
    const url = buildMapboxStaticUrl({
      lat: 37,
      lng: 127,
      width: 100,
      height: 100,
      zoom: 14,
      token: "tk",
    });
    expect(url).toMatch(/,14,0\//);
  });

  it("음수 좌표(서반구·남반구) 도 처리한다", () => {
    const url = buildMapboxStaticUrl({
      lat: -33.8688,
      lng: -73.9857,
      width: 100,
      height: 100,
      zoom: 10,
      token: "tk",
    });
    expect(url).toContain("/-73.985700,-33.868800,10,0/");
  });
});
