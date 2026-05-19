import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { IconBell } from "../IconBell";

describe("IconBell", () => {
  it("(AC-011) SVG 요소가 aria-hidden='true'로 렌더된다", () => {
    const { container } = render(<IconBell />);
    const svg = container.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(svg).toHaveAttribute("aria-hidden", "true");
  });

  it("(AC-017) size prop 기본값 24가 width/height에 반영된다", () => {
    const { container } = render(<IconBell />);
    const svg = container.querySelector("svg");
    expect(svg).toHaveAttribute("width", "24");
    expect(svg).toHaveAttribute("height", "24");
  });

  it("(AC-017) size prop 변경 시 width/height에 반영된다", () => {
    const { container } = render(<IconBell size={32} />);
    const svg = container.querySelector("svg");
    expect(svg).toHaveAttribute("width", "32");
    expect(svg).toHaveAttribute("height", "32");
  });

  it("(AC-017) color prop 기본값 currentColor가 stroke에 반영된다", () => {
    const { container } = render(<IconBell />);
    const svg = container.querySelector("svg");
    expect(svg).toHaveAttribute("stroke", "currentColor");
  });

  it("(AC-017) color prop 변경 시 stroke에 반영된다", () => {
    const { container } = render(<IconBell color="#FF0000" />);
    const svg = container.querySelector("svg");
    expect(svg).toHaveAttribute("stroke", "#FF0000");
  });

  it("(AC-011) viewBox가 '0 0 24 24'이다", () => {
    const { container } = render(<IconBell />);
    const svg = container.querySelector("svg");
    expect(svg).toHaveAttribute("viewBox", "0 0 24 24");
  });

  it("(AC-011) 2개의 path가 존재한다 (bell + clapper)", () => {
    const { container } = render(<IconBell />);
    const paths = container.querySelectorAll("svg > path");
    expect(paths.length).toBe(2);
  });
});
