import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import PinPhotoInline from "./PinPhotoInline";

describe("PinPhotoInline", () => {
  it("원본/썸네일 사진을 렌더하고 별도 복귀 버튼은 두지 않는다", () => {
    render(
      <PinPhotoInline
        thumbnailUrl="thumb.jpg"
        photoUrl="photo.jpg"
        onBack={vi.fn()}
      />,
    );
    expect(screen.getByAltText("추억 사진")).toBeInTheDocument();
    // 되돌아가기 버튼 제거 — 사진 영역 탭으로만 복귀한다.
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("사진 영역 클릭 시 onBack 호출(메모 복귀)", () => {
    const onBack = vi.fn();
    render(
      <PinPhotoInline
        thumbnailUrl="thumb.jpg"
        photoUrl="photo.jpg"
        onBack={onBack}
      />,
    );
    // 사진 이미지를 클릭하면 컨테이너 onClick 으로 버블링되어 onBack 호출.
    fireEvent.click(screen.getByAltText("추억 사진"));
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
