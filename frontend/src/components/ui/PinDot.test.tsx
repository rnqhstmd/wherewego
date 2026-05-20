import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { PinDot } from "./PinDot";

describe("PinDot", () => {
  it("(AC-19) type='place' → data-testid 'pin-dot-place', data-tag 'place', DIV 요소", () => {
    const { container } = render(<PinDot type="place" />);
    const el = container.querySelector(
      '[data-testid="pin-dot-place"]',
    ) as HTMLElement | null;
    expect(el).toBeInTheDocument();
    expect(el).toHaveAttribute("data-tag", "place");
    expect(el?.tagName).toBe("DIV");
  });

  it("(AC-20) type='memory' → data-testid 'pin-dot-memory', data-tag 'memory', SVG 요소", () => {
    const { container } = render(<PinDot type="memory" />);
    const el = container.querySelector(
      '[data-testid="pin-dot-memory"]',
    ) as SVGElement | null;
    expect(el).toBeInTheDocument();
    expect(el).toHaveAttribute("data-tag", "memory");
    expect(el?.tagName.toLowerCase()).toBe("svg");
  });
});
