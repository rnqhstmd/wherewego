import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { PinTag } from "./PinTag";
import type { PinTag as PinTagValue } from "@/lib/api/types";
import { PIN_COLORS } from "@/lib/pin/markers";

/**
 * 색은 인라인 style로 직접 명시되므로 backgroundColor/color/borderColor 확인.
 * Tailwind 클래스(bg-pin-{kind}, text-pin-{kind})는 더 이상 사용하지 않는다.
 * (dev hot reload 캐시로 미적용되는 케이스 회피)
 */
function rgbOf(hex: string): string {
  // jsdom은 hex → rgb로 정규화하므로 비교용 helper.
  const v = hex.replace("#", "");
  const r = parseInt(v.slice(0, 2), 16);
  const g = parseInt(v.slice(2, 4), 16);
  const b = parseInt(v.slice(4, 6), 16);
  return `rgb(${r}, ${g}, ${b})`;
}

describe("PinTag", () => {
  it("(AC) type='REEL', active=true → '발견' + reel 글리프 + REEL 색 배경", () => {
    render(<PinTag type="REEL" active />);
    const btn = screen.getByRole("button", { name: /발견/ });
    expect(btn).toHaveAttribute("data-active", "true");
    expect(btn).toHaveAttribute("data-tag", "REEL");
    expect(btn.style.backgroundColor).toBe(rgbOf(PIN_COLORS.reel));
    expect(btn.style.color).toBe("rgb(255, 255, 255)");
    expect(btn.querySelector('[data-testid="pin-glyph-reel"]')).toBeInTheDocument();
  });

  it("(AC) type='WISH', active=false → '위시' + wish 글리프 + 자기 색 텍스트", () => {
    render(<PinTag type="WISH" active={false} />);
    const btn = screen.getByRole("button", { name: /위시/ });
    expect(btn).toHaveAttribute("data-active", "false");
    expect(btn).toHaveAttribute("data-tag", "WISH");
    expect(btn.style.color).toBe(rgbOf(PIN_COLORS.wish));
    expect(btn.style.borderColor).toBe(rgbOf(PIN_COLORS.wish));
    expect(btn.querySelector('[data-testid="pin-glyph-wish"]')).toBeInTheDocument();
  });

  it("(AC) type='MEMORY', active=true → '추억' + memory 글리프 + MEMORY 색 배경", () => {
    render(<PinTag type="MEMORY" active />);
    const btn = screen.getByRole("button", { name: /추억/ });
    expect(btn).toHaveAttribute("data-active", "true");
    expect(btn).toHaveAttribute("data-tag", "MEMORY");
    expect(btn.style.backgroundColor).toBe(rgbOf(PIN_COLORS.memory));
    expect(btn.style.color).toBe("rgb(255, 255, 255)");
    expect(btn.querySelector('[data-testid="pin-glyph-memory"]')).toBeInTheDocument();
  });

  // M1 fallback: 알 수 없는 enum이 type으로 들어와도 안전하게 WISH 메타로 폴백.
  // 사용자 확인된 안전장치(설계 §M1).
  it("(M1 fallback) 알 수 없는 enum → '위시' 레이블 + pin-glyph-wish 글리프", () => {
    // 의도적 타입 우회 — M1 fallback 검증 (알 수 없는 enum 값이 들어와도 WISH로 안전하게 렌더링되는지)
    render(<PinTag type={"PLACE" as PinTagValue} />);
    const btn = screen.getByRole("button", { name: /위시/ });
    expect(btn.querySelector('[data-testid="pin-glyph-wish"]')).toBeInTheDocument();
  });
});
