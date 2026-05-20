import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import PinPopupMemoEditor from "./PinPopupMemoEditor";
import { MEMO_MAX_LENGTH } from "@/lib/pin/constants";
import { colors } from "@/lib/design/tokens";

describe("PinPopupMemoEditor", () => {
  it("(AC-15) memoLength >= MEMO_MAX_LENGTH - 50 이면 카운터가 colors.cta", () => {
    render(
      <PinPopupMemoEditor
        initialMemo=""
        pending={false}
        error={null}
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    const textarea = screen.getByRole("textbox");
    fireEvent.change(textarea, {
      target: { value: "a".repeat(MEMO_MAX_LENGTH - 50) },
    });
    const counter = screen.getByText(
      `${MEMO_MAX_LENGTH - 50}/${MEMO_MAX_LENGTH}`,
    );
    expect(counter).toHaveStyle({ color: colors.cta });
  });

  it("(AC-16) 초기값과 동일 텍스트이면 저장 버튼 disabled", () => {
    render(
      <PinPopupMemoEditor
        initialMemo="원본"
        pending={false}
        error={null}
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });

  it("(AC-16-a) pending=true 이면 textarea와 저장 버튼 모두 disabled", () => {
    render(
      <PinPopupMemoEditor
        initialMemo=""
        pending={true}
        error={null}
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole("textbox")).toBeDisabled();
    expect(screen.getByRole("button", { name: "저장 중..." })).toBeDisabled();
  });

  it("(AC-16-b) error prop이 null → '저장 실패'로 변경되어도 입력값 보존", () => {
    const { rerender } = render(
      <PinPopupMemoEditor
        initialMemo=""
        pending={false}
        error={null}
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    const textarea = screen.getByRole("textbox") as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: "사용자가 입력 중" } });

    rerender(
      <PinPopupMemoEditor
        initialMemo=""
        pending={false}
        error="저장 실패"
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe(
      "사용자가 입력 중",
    );
  });
});
