import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { PinTag } from "./PinTag";
import { colors } from "@/lib/design/tokens";

describe("PinTag", () => {
  it("(AC-21) type='place', active=false → 레이블 '● 장소', background transparent", () => {
    render(<PinTag type="place" active={false} />);
    const btn = screen.getByRole("button", { name: "● 장소" });
    expect(btn).toHaveAttribute("data-active", "false");
    expect(btn).toHaveAttribute("data-tag", "place");
    expect(btn).toHaveStyle({ background: "transparent" });
  });

  it("(AC-22) type='memory', active=true → 레이블 '♡ 추억', background이 colors.pinMemory 토큰과 일치", () => {
    render(<PinTag type="memory" active={true} />);
    const btn = screen.getByRole("button", { name: "♡ 추억" });
    expect(btn).toHaveAttribute("data-active", "true");
    expect(btn).toHaveAttribute("data-tag", "memory");
    expect(btn).toHaveStyle({ background: colors.pinMemory });
  });
});
