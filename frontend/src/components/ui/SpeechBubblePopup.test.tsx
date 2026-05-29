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
  pinType: "wish" as const,
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

  it("memoThumbnail 전달 시 메모 우측 썸네일 슬롯 렌더", () => {
    render(
      <SpeechBubblePopup
        {...baseProps}
        memoThumbnail={<img alt="추억 사진" src="thumb.jpg" />}
      />,
    );
    expect(screen.getByAltText("추억 사진")).toBeInTheDocument();
  });

  it("showExpandedPhoto=false 면 메모를 렌더하고 expandedPhoto 는 aria-hidden", () => {
    render(
      <SpeechBubblePopup
        {...baseProps}
        expandedPhoto={<div>PHOTO_MARKER</div>}
        showExpandedPhoto={false}
      />,
    );
    // 메모는 정상 노출, 사진 노드는 DOM 에 있으나 aria-hidden(크로스페이드 대기)
    expect(screen.getByText("메모")).toBeInTheDocument();
    const photo = screen.getByText("PHOTO_MARKER").parentElement;
    expect(photo).toHaveAttribute("aria-hidden", "true");
  });

  it("showExpandedPhoto=true 면 expandedPhoto 활성·메모는 aria-hidden", () => {
    render(
      <SpeechBubblePopup
        {...baseProps}
        memoThumbnail={<img alt="추억 사진" src="thumb.jpg" />}
        expandedPhoto={<div>PHOTO_MARKER</div>}
        showExpandedPhoto={true}
      />,
    );
    const photo = screen.getByText("PHOTO_MARKER").parentElement;
    expect(photo).toHaveAttribute("aria-hidden", "false");
    // 메모 썸네일을 감싼 메모 행은 aria-hidden 처리
    const memoRow = screen.getByAltText("추억 사진").closest('[aria-hidden]');
    expect(memoRow).toHaveAttribute("aria-hidden", "true");
  });

  it("expandedPhoto 미전달이면 showExpandedPhoto=true 여도 메모 유지 (기존 동작)", () => {
    render(<SpeechBubblePopup {...baseProps} showExpandedPhoto={true} />);
    expect(screen.getByText("메모")).toBeInTheDocument();
  });
});
