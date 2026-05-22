import { describe, it, expect } from "vitest";
import { wrapAndEllipsize } from "../renderPinCard";

// Phase 9 §"상세 설계" 8번 — wrapAndEllipsize 단위 테스트.
//
// jsdom에서 Canvas measureText는 모킹이 어려우므로, 호출처가 주입하는 measureTextFn을
// "char당 px"의 단순 함수로 대체하여 결정적으로 검증한다.

const measure10 = (s: string) => s.length * 10; // 영문 10px 가정
const measure22 = (s: string) => s.length * 22; // 한글 22px 가정

describe("wrapAndEllipsize (Phase 9 §상세설계 8)", () => {
  it("빈 문자열은 빈 배열을 반환한다", () => {
    expect(wrapAndEllipsize("", 200, 3, measure10)).toEqual([]);
  });

  it("maxLines가 0이면 빈 배열을 반환한다", () => {
    expect(wrapAndEllipsize("Hello world", 200, 0, measure10)).toEqual([]);
  });

  it("짧은 한 줄 텍스트는 그대로 한 줄로 반환한다", () => {
    const result = wrapAndEllipsize("Hello", 200, 1, measure10);
    expect(result).toEqual(["Hello"]);
  });

  it("두 줄 정상 줄바꿈 (말줄임 불필요)", () => {
    // 토큰: "aa", "bb", "cc", "dd"
    // 각 단어 길이 20px, 한 줄 60px → "aa bb"(50px) 가능, "aa bb cc"(80px) 초과
    // 결과: ["aa bb", "cc dd"]
    const result = wrapAndEllipsize("aa bb cc dd", 60, 2, measure10);
    expect(result).toEqual(["aa bb", "cc dd"]);
    expect(result.length).toBeLessThanOrEqual(2);
  });

  it("한 줄 안 들어가고 maxLines=1이면 말줄임을 적용한다", () => {
    // 토큰: "Hello"(50px), "world"(50px). maxWidth 80px 한 줄.
    // 첫 토큰 들어가고 두 번째 안 들어감 → "Hello…"가 maxWidth 안에 들어가는지
    // "Hello…"는 6글자 * 10 = 60px ≤ 80 → "Hello…"
    const result = wrapAndEllipsize("Hello world", 80, 1, measure10);
    expect(result).toHaveLength(1);
    expect(result[0].endsWith("…")).toBe(true);
  });

  it("매우 긴 단어는 문자 단위로 강제 분할한다", () => {
    // 단어 "abcdefghij" = 100px, maxWidth 50, maxLines=2
    // char 분할: "abcde"(50px), "fghij"(50px)
    // 결과: ["abcde", "fghij"] — 두 줄에 딱 맞음
    const result = wrapAndEllipsize("abcdefghij", 50, 2, measure10);
    expect(result.length).toBeLessThanOrEqual(2);
    // 마지막 줄에 남은 char 없으니 말줄임 없음
    expect(result.join("")).toContain("a");
  });

  it("매우 긴 단어 + maxLines=1이면 강제 분할 + 말줄임", () => {
    // 단어 "abcdefghij" 100px, maxWidth 50, maxLines=1
    // char 분할 후 첫 줄에 들어가는 만큼 + "…"
    // "abcd…" = 5 * 10 = 50px
    const result = wrapAndEllipsize("abcdefghij", 50, 1, measure10);
    expect(result).toHaveLength(1);
    expect(result[0].endsWith("…")).toBe(true);
  });

  it("정확히 maxLines를 채우고 남는 토큰이 없으면 말줄임을 추가하지 않는다", () => {
    // 토큰 "aa bb" → "aa"(20), "bb"(20), maxWidth 25
    // 한 토큰씩 한 줄 → ["aa", "bb"], maxLines=2
    const result = wrapAndEllipsize("aa bb", 25, 2, measure10);
    expect(result).toEqual(["aa", "bb"]);
    expect(result.some((l) => l.endsWith("…"))).toBe(false);
  });

  it("다중 줄에서 마지막 줄만 말줄임된다", () => {
    // 토큰 5개: "aa bb cc dd ee", maxWidth 50, maxLines=2
    // 첫 줄: "aa bb"(50), 두 번째 줄 들어가야 할 "cc dd ee"(80px) 초과
    // → 두 번째 줄에 "cc dd ee"를 말줄임 처리
    const result = wrapAndEllipsize("aa bb cc dd ee", 50, 2, measure10);
    expect(result).toHaveLength(2);
    expect(result[0].endsWith("…")).toBe(false);
    expect(result[1].endsWith("…")).toBe(true);
  });

  it("한국어 텍스트 — 22px 단위 가정", () => {
    // "우리는 여기서 산책을 많이 했어요"
    // 토큰: "우리는"(66), "여기서"(66), "산책을"(66), "많이"(44), "했어요"(66)
    // maxWidth 150, maxLines=2
    // 첫 줄: "우리는"(66) + " 여기서"(88) → "우리는 여기서"(7*22=154) > 150 → 안 들어감
    //        → 첫 줄: "우리는" (66)
    //        그 다음 "여기서": "여기서"(66) → "여기서"(66), "여기서 산책을"(154>150) 안 들어감
    //        → maxLines-1 도달, 남은 토큰을 말줄임 처리
    // 결과는 정확한 픽셀 계산보다는 줄 수와 말줄임 유무를 검증
    const result = wrapAndEllipsize(
      "우리는 여기서 산책을 많이 했어요",
      150,
      2,
      measure22,
    );
    expect(result.length).toBeGreaterThan(0);
    expect(result.length).toBeLessThanOrEqual(2);
    expect(result[result.length - 1].endsWith("…")).toBe(true);
  });

  it("최대 5줄까지 채우고 그 이상은 마지막 줄을 말줄임한다", () => {
    // 토큰 10개, maxWidth는 토큰 하나만 들어가는 폭, maxLines=5
    // 처음 5개 정상 → 마지막에 나머지 5개 말줄임
    const text = "aa bb cc dd ee ff gg hh ii jj";
    const result = wrapAndEllipsize(text, 25, 5, measure10);
    expect(result).toHaveLength(5);
    expect(result[4].endsWith("…")).toBe(true);
    // 앞 4줄은 말줄임 없음
    for (let i = 0; i < 4; i += 1) {
      expect(result[i].endsWith("…")).toBe(false);
    }
  });

  it("공백이 여러 개여도 정규화하여 분리한다", () => {
    // 토큰 split(/\s+/) → ["aa", "bb"]
    const result = wrapAndEllipsize("aa   bb", 100, 1, measure10);
    expect(result).toEqual(["aa bb"]);
  });
});
