import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { SpeechBubblePopup } from "./SpeechBubblePopup";

const baseProps = {
  pinX: 100,
  pinY: 100,
  memo: "메모",
  addr: "서울 강남구",
  author: "tester",
  date: "2025-01-01",
  pinType: "place" as const,
};

describe("SpeechBubblePopup", () => {
  it("(AC-23) place 있음 → 장소명 + addr 둘 다 렌더", () => {
    render(<SpeechBubblePopup {...baseProps} place="스타벅스 강남점" />);
    expect(screen.getByText("스타벅스 강남점")).toBeInTheDocument();
    expect(screen.getByText("서울 강남구")).toBeInTheDocument();
  });

  it("(AC-24) place 있다가 null로 변경 → place 텍스트가 사라지고 addr만 렌더", () => {
    const { rerender } = render(
      <SpeechBubblePopup {...baseProps} place="스타벅스 강남점" />,
    );
    expect(screen.getByText("스타벅스 강남점")).toBeInTheDocument();
    expect(screen.getByText("서울 강남구")).toBeInTheDocument();

    rerender(<SpeechBubblePopup {...baseProps} place={null} />);
    expect(screen.queryByText("스타벅스 강남점")).not.toBeInTheDocument();
    expect(screen.getByText("서울 강남구")).toBeInTheDocument();
  });

  it("(AC-25) footerContent 전달 시 footer 영역 렌더", () => {
    render(
      <SpeechBubblePopup
        {...baseProps}
        place="X"
        footerContent={<div>FOOTER_MARKER</div>}
      />,
    );
    expect(screen.getByText("FOOTER_MARKER")).toBeInTheDocument();
  });
});
