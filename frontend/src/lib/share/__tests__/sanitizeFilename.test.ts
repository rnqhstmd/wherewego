import { describe, it, expect } from "vitest";
import { sanitizeFilename } from "../sanitizeFilename";

describe("sanitizeFilename (Phase 9 BR-5)", () => {
  it("일반 한글 + 공백을 _로 치환한다", () => {
    expect(sanitizeFilename("강남역 5번 출구")).toBe("강남역_5번_출구");
  });

  it("영문 + 공백을 _로 치환한다", () => {
    expect(sanitizeFilename("My Place")).toBe("My_Place");
  });

  it("파일 시스템 금지 문자를 제거한다", () => {
    expect(sanitizeFilename("a/b\\c:d*e?f")).toBe("abcdef");
  });

  it('따옴표/꺾쇠/파이프 같은 추가 금지 문자도 제거한다', () => {
    expect(sanitizeFilename('a"b<c>d|e')).toBe("abcde");
  });

  it("빈 문자열은 pin으로 폴백한다", () => {
    expect(sanitizeFilename("")).toBe("pin");
  });

  it("공백만 있는 입력은 pin으로 폴백한다", () => {
    expect(sanitizeFilename("   ")).toBe("pin");
  });

  it("제어문자만 있는 입력은 pin으로 폴백한다", () => {
    expect(sanitizeFilename("\x00\x01\x02")).toBe("pin");
  });

  it("제어문자가 섞여 있어도 제어문자만 제거한다", () => {
    expect(sanitizeFilename("café\x00bar")).toBe("cafébar");
  });

  it("null 입력은 pin으로 폴백한다", () => {
    // 타입 시그니처는 string이지만, 호출처 안전성을 위한 런타임 가드.
    expect(sanitizeFilename(null as unknown as string)).toBe("pin");
  });

  it("undefined 입력은 pin으로 폴백한다", () => {
    expect(sanitizeFilename(undefined as unknown as string)).toBe("pin");
  });

  it("탭/개행 등 공백 종류도 _로 치환한다", () => {
    expect(sanitizeFilename("a\tb\nc")).toBe("a_b_c");
  });

  it("좌우 공백을 트림한 뒤 치환한다", () => {
    expect(sanitizeFilename("  hello world  ")).toBe("hello_world");
  });
});
