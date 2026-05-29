import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import PinPhotoInline from "./PinPhotoInline";

describe("PinPhotoInline", () => {
  it("원본/썸네일 사진과 ↩ 복귀 버튼을 렌더한다", () => {
    render(
      <PinPhotoInline
        thumbnailUrl="thumb.jpg"
        photoUrl="photo.jpg"
        onBack={vi.fn()}
      />,
    );
    expect(screen.getByAltText("추억 사진")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "메모로 돌아가기" }),
    ).toBeInTheDocument();
  });

  it("↩ 버튼 클릭 시 onBack 호출", () => {
    const onBack = vi.fn();
    render(
      <PinPhotoInline
        thumbnailUrl="thumb.jpg"
        photoUrl="photo.jpg"
        onBack={onBack}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "메모로 돌아가기" }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it("원본 onLoad 후 원본 opacity 1 / 썸네일 placeholder opacity 0", () => {
    render(
      <PinPhotoInline
        thumbnailUrl="thumb.jpg"
        photoUrl="photo.jpg"
        onBack={vi.fn()}
      />,
    );
    const photo = screen.getByAltText("추억 사진");
    // 로드 전: 원본 흐림(opacity 0)
    expect(photo).toHaveStyle({ opacity: "0" });
    fireEvent.load(photo);
    expect(photo).toHaveStyle({ opacity: "1" });
  });
});
