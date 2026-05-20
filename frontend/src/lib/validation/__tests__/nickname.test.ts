import { describe, it, expect } from "vitest";
import { validateNickname, sanitizeNickname } from "../nickname";

describe("validateNickname (AC-006, EC-007)", () => {
  it("빈 문자열은 too_short 사유로 invalid", () => {
    const r = validateNickname("");
    expect(r.valid).toBe(false);
    if (!r.valid) expect(r.reason).toBe("too_short");
  });

  it("1자 한글은 too_short 사유로 invalid", () => {
    const r = validateNickname("가");
    expect(r.valid).toBe(false);
    if (!r.valid) expect(r.reason).toBe("too_short");
  });

  it("2자 한글은 valid", () => {
    const r = validateNickname("가나");
    expect(r.valid).toBe(true);
  });

  it("12자 영문은 valid", () => {
    const r = validateNickname("abcdefghijkl");
    expect(r.valid).toBe(true);
  });

  it("13자는 too_long 사유로 invalid", () => {
    const r = validateNickname("abcdefghijklm");
    expect(r.valid).toBe(false);
    if (!r.valid) expect(r.reason).toBe("too_long");
  });

  it("특수문자 포함은 invalid_char 사유로 invalid", () => {
    const r = validateNickname("ab!c");
    expect(r.valid).toBe(false);
    if (!r.valid) expect(r.reason).toBe("invalid_char");
  });

  it("한글+영문+숫자 혼합 (2~12자 내)은 valid", () => {
    const r = validateNickname("test123가나");
    expect(r.valid).toBe(true);
  });

  it("공백만 있는 입력은 invalid (특수문자로 invalid_char)", () => {
    const r = validateNickname("  ");
    expect(r.valid).toBe(false);
    if (!r.valid) {
      expect(["invalid_char", "too_short"]).toContain(r.reason);
    }
  });
});

describe("sanitizeNickname", () => {
  it("특수문자를 제거한다", () => {
    expect(sanitizeNickname("ab!c@d")).toBe("abcd");
  });

  it("12자로 절단한다", () => {
    expect(sanitizeNickname("abcdefghij12345")).toBe("abcdefghij12");
  });

  it("한글을 보존한다", () => {
    expect(sanitizeNickname("가나다라")).toBe("가나다라");
  });

  it("공백을 제거한다", () => {
    expect(sanitizeNickname("a b")).toBe("ab");
  });
});
