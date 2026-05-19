import { describe, it, expect, beforeEach } from "vitest";
import { kakaoState } from "../kakao-state";

const SESSION_KEY = "maygo:kakao-oauth-state";

describe("kakaoState.generate", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it("빈 문자열이 아닌 state 값을 반환한다", () => {
    const state = kakaoState.generate();
    expect(typeof state).toBe("string");
    expect(state.length).toBeGreaterThan(0);
  });

  it("호출마다 다른 값을 반환한다", () => {
    const s1 = kakaoState.generate();
    const s2 = kakaoState.generate();
    expect(s1).not.toBe(s2);
  });

  it("sessionStorage에 저장된다 (maygo:kakao-oauth-state 키)", () => {
    const state = kakaoState.generate();
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBe(state);
  });
});

describe("kakaoState.validate", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it("generate 후 동일 값 validate → true", () => {
    const state = kakaoState.generate();
    expect(kakaoState.validate(state)).toBe(true);
  });

  it("generate 후 다른 값 validate → false", () => {
    kakaoState.generate();
    expect(kakaoState.validate("different-value")).toBe(false);
  });

  it("generate 없이 validate → false", () => {
    expect(kakaoState.validate("anything")).toBe(false);
  });

  it("validate 후 sessionStorage에서 제거된다 (1회용)", () => {
    const state = kakaoState.generate();
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBe(state);
    kakaoState.validate(state);
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it("동일 값으로 두 번 validate → 두 번째는 false (1회용)", () => {
    const state = kakaoState.generate();
    expect(kakaoState.validate(state)).toBe(true);
    expect(kakaoState.validate(state)).toBe(false);
  });

  it("null → false", () => {
    kakaoState.generate();
    expect(kakaoState.validate(null)).toBe(false);
  });

  it("undefined → false", () => {
    kakaoState.generate();
    expect(kakaoState.validate(undefined)).toBe(false);
  });

  it("빈 문자열 → false", () => {
    kakaoState.generate();
    expect(kakaoState.validate("")).toBe(false);
  });
});
