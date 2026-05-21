import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { PinDot, type PinDotType } from "./PinDot";

describe("PinDot", () => {
  it("(AC) type='reel' → data-testid 'pin-glyph-reel' 글리프 렌더", () => {
    const { container } = render(<PinDot type="reel" />);
    const el = container.querySelector(
      '[data-testid="pin-glyph-reel"]',
    ) as SVGElement | null;
    expect(el).toBeInTheDocument();
    expect(el?.tagName.toLowerCase()).toBe("svg");
  });

  it("(AC) type='wish' → data-testid 'pin-glyph-wish' 글리프 렌더", () => {
    const { container } = render(<PinDot type="wish" />);
    const el = container.querySelector(
      '[data-testid="pin-glyph-wish"]',
    ) as SVGElement | null;
    expect(el).toBeInTheDocument();
    expect(el?.tagName.toLowerCase()).toBe("svg");
  });

  it("(AC) type='memory' → data-testid 'pin-glyph-memory' 글리프 렌더", () => {
    const { container } = render(<PinDot type="memory" />);
    const el = container.querySelector(
      '[data-testid="pin-glyph-memory"]',
    ) as SVGElement | null;
    expect(el).toBeInTheDocument();
    expect(el?.tagName.toLowerCase()).toBe("svg");
  });

  // M1 fallback: 알 수 없는 타입이 들어와도 안전하게 WishGlyph로 폴백.
  // 사용자 확인된 안전장치(설계 §M1).
  it("(M1 fallback) 알 수 없는 type → pin-glyph-wish 글리프 렌더", () => {
    const { container } = render(<PinDot type={"place" as PinDotType} />);
    const el = container.querySelector(
      '[data-testid="pin-glyph-wish"]',
    ) as SVGElement | null;
    expect(el).toBeInTheDocument();
  });
});
