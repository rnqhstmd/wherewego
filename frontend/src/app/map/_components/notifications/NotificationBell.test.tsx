import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import { NotificationBell } from "./NotificationBell";

describe("NotificationBell", () => {
  it("(AC-13) unreadCount > 0 + 정상 연결 → 빨간 점 표시", () => {
    const { container } = render(
      <NotificationBell
        unreadCount={1}
        connectionState="open"
        onClick={vi.fn()}
      />,
    );

    const dot = container.querySelector('span[aria-hidden="true"]');
    expect(dot).not.toBeNull();
    // pinNew 색상은 디자인 토큰. 단순히 회색이 아니고 backgroundColor가 채워져 있는지 확인.
    const style = (dot as HTMLElement).style;
    expect(style.background).not.toBe("");
    // failed가 아니므로 회색(rgba(120,120,120,0.9))이 아님
    expect(style.background).not.toContain("120, 120, 120");
  });

  it("(AC-13/Q8) connectionState=failed → 회색 점 + tooltip 안내", () => {
    const { container } = render(
      <NotificationBell
        unreadCount={0}
        connectionState="failed"
        variant="desktop"
        onClick={vi.fn()}
      />,
    );

    // 회색 점 노출
    const dot = container.querySelector('span[aria-hidden="true"]');
    expect(dot).not.toBeNull();
    const dotStyle = (dot as HTMLElement).style;
    // 회색(rgba(120,120,120,0.9))
    expect(dotStyle.background).toContain("120, 120, 120");

    // 버튼 aria-label에 "새로고침" 안내가 포함
    const button = screen.getByRole("button");
    expect(button.getAttribute("aria-label")).toContain("새로고침");

    // 데스크탑 hover 시 tooltip 노출 (role="tooltip")
    fireEvent.mouseEnter(button);
    const tooltip = screen.getByRole("tooltip");
    expect(tooltip).toBeInTheDocument();
    expect(tooltip.textContent).toContain("새로고침");
  });

  it("클릭 시 onClick 콜백 호출", () => {
    const onClick = vi.fn();
    render(
      <NotificationBell
        unreadCount={0}
        connectionState="open"
        onClick={onClick}
      />,
    );

    fireEvent.click(screen.getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
