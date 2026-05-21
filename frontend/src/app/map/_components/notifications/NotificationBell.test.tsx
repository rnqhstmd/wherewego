import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import { NotificationBell } from "./NotificationBell";

describe("NotificationBell", () => {
  it("(AC-13) unreadCount > 0 → 빨간 점 표시", () => {
    const { container } = render(
      <NotificationBell unreadCount={1} onClick={vi.fn()} />,
    );

    const dot = container.querySelector('span[aria-hidden="true"]');
    expect(dot).not.toBeNull();
    const style = (dot as HTMLElement).style;
    expect(style.background).not.toBe("");
  });

  it("unreadCount === 0 → 점 미표시", () => {
    const { container } = render(
      <NotificationBell unreadCount={0} onClick={vi.fn()} />,
    );

    const dot = container.querySelector('span[aria-hidden="true"]');
    expect(dot).toBeNull();
  });

  it("데스크탑 hover 시 tooltip 노출", () => {
    render(
      <NotificationBell
        unreadCount={0}
        variant="desktop"
        onClick={vi.fn()}
      />,
    );

    const button = screen.getByRole("button");
    fireEvent.mouseEnter(button);
    const tooltip = screen.getByRole("tooltip");
    expect(tooltip).toBeInTheDocument();
    expect(tooltip.textContent).toContain("알림");
  });

  it("클릭 시 onClick 콜백 호출", () => {
    const onClick = vi.fn();
    render(<NotificationBell unreadCount={0} onClick={onClick} />);

    fireEvent.click(screen.getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
