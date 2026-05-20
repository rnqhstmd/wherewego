import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BtnKakao } from "../BtnKakao";
import { colors } from "@/lib/design/tokens";

describe("BtnKakao", () => {
  it("(AC-003) children이 렌더된다", () => {
    render(<BtnKakao>카카오로 시작</BtnKakao>);
    expect(
      screen.getByRole("button", { name: "카카오로 시작" }),
    ).toBeInTheDocument();
  });

  it("(AC-003) onClick prop이 클릭 시 호출된다", async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(<BtnKakao onClick={onClick}>로그인</BtnKakao>);
    await user.click(screen.getByRole("button", { name: "로그인" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("(AC-016) background style이 colors.kakao(#FEE500)이다", () => {
    render(<BtnKakao>로그인</BtnKakao>);
    const btn = screen.getByRole("button", { name: "로그인" });
    expect(btn).toHaveStyle({ background: colors.kakao });
  });

  it("(AC-003) disabled prop이 true일 때 disabled 속성이 적용된다", () => {
    render(<BtnKakao disabled>로그인</BtnKakao>);
    const btn = screen.getByRole("button", { name: "로그인" });
    expect(btn).toBeDisabled();
  });

  it("(AC-003) disabled true일 때 opacity가 0.5이고 클릭이 호출되지 않는다", async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(
      <BtnKakao disabled onClick={onClick}>
        로그인
      </BtnKakao>,
    );
    const btn = screen.getByRole("button", { name: "로그인" });
    expect(btn).toHaveStyle({ opacity: "0.5" });
    await user.click(btn);
    expect(onClick).not.toHaveBeenCalled();
  });
});
