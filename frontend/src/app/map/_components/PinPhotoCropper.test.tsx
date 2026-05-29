import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import type { Area } from "react-easy-crop";
import PinPhotoCropper from "./PinPhotoCropper";

// react-easy-crop 은 jsdom 미지원 ResizeObserver/레이아웃 측정을 사용하므로
// mock 으로 대체하고, onCropComplete 를 즉시 발화시켜 크롭 영역 전달을 흉내낸다.
vi.mock("react-easy-crop", () => {
  const FAKE_AREA: Area = { x: 10, y: 20, width: 200, height: 200 };
  return {
    __esModule: true,
    default: ({
      onCropComplete,
    }: {
      onCropComplete?: (a: Area, b: Area) => void;
    }) => {
      // mount 시 한 번 크롭 완료를 흉내내 croppedAreaPixels 를 채운다.
      onCropComplete?.(FAKE_AREA, FAKE_AREA);
      return <div data-testid="cropper" />;
    },
  };
});

// canvas/Image 디코딩 회피 — 헬퍼 자체는 별도 책임. 흐름만 검증.
vi.mock("@/lib/image/cropImage", () => ({
  getCroppedSquareFile: vi.fn(
    async (file: File) =>
      new File(["cropped"], file.name, { type: "image/jpeg" }),
  ),
}));

function makeFile(name = "photo.png") {
  return new File(["x"], name, { type: "image/png" });
}

beforeEach(() => {
  // jsdom createObjectURL polyfill.
  if (typeof URL.createObjectURL !== "function") {
    URL.createObjectURL = vi.fn(() => "blob:mock");
  }
  if (typeof URL.revokeObjectURL !== "function") {
    URL.revokeObjectURL = vi.fn();
  }
});

describe("PinPhotoCropper", () => {
  it("크롭 영역과 확인/취소 버튼을 렌더한다", () => {
    render(
      <PinPhotoCropper
        file={makeFile()}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByTestId("cropper")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확인" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "취소" })).toBeInTheDocument();
    // 확대 슬라이더 제거 — 데스크톱 휠 / 모바일 핀치로 줌.
    expect(screen.queryByRole("slider")).not.toBeInTheDocument();
  });

  it("취소 클릭 시 onCancel 호출", () => {
    const onCancel = vi.fn();
    render(
      <PinPhotoCropper
        file={makeFile()}
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("확인 클릭 시 정사각 크롭 결과 File 로 onConfirm 호출", async () => {
    const onConfirm = vi.fn();
    render(
      <PinPhotoCropper
        file={makeFile("memory.png")}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "확인" }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    const passed = onConfirm.mock.calls[0][0] as File;
    expect(passed).toBeInstanceOf(File);
    expect(passed.type).toBe("image/jpeg");
  });
});
