import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { PinTag } from "./PinTag";
import type { PinTag as PinTagValue } from "@/lib/api/types";

describe("PinTag", () => {
  it("(AC) type='REEL', active=true → '발견' 레이블 + pin-glyph-reel 글리프 + bg-pin-reel 클래스", () => {
    render(<PinTag type="REEL" active />);
    const btn = screen.getByRole("button", { name: /발견/ });
    expect(btn).toHaveAttribute("data-active", "true");
    expect(btn).toHaveAttribute("data-tag", "REEL");
    expect(btn.className).toContain("bg-pin-reel");
    expect(btn.querySelector('[data-testid="pin-glyph-reel"]')).toBeInTheDocument();
  });

  it("(AC) type='WISH', active=false → '위시' 레이블 + pin-glyph-wish 글리프 + bg-transparent inactive 클래스", () => {
    render(<PinTag type="WISH" active={false} />);
    const btn = screen.getByRole("button", { name: /위시/ });
    expect(btn).toHaveAttribute("data-active", "false");
    expect(btn).toHaveAttribute("data-tag", "WISH");
    expect(btn.className).toContain("bg-transparent");
    expect(btn.className).toContain("text-pin-wish");
    expect(btn.querySelector('[data-testid="pin-glyph-wish"]')).toBeInTheDocument();
  });

  it("(AC) type='MEMORY', active=true → '추억' 레이블 + pin-glyph-memory 글리프 + bg-pin-memory 클래스", () => {
    render(<PinTag type="MEMORY" active />);
    const btn = screen.getByRole("button", { name: /추억/ });
    expect(btn).toHaveAttribute("data-active", "true");
    expect(btn).toHaveAttribute("data-tag", "MEMORY");
    expect(btn.className).toContain("bg-pin-memory");
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
