import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import VisitToast from "./VisitToast";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

function makePin(overrides: Partial<PinSummaryResponse> = {}): PinSummaryResponse {
  return {
    id: 1,
    groupId: 1,
    createdBy: 1,
    createdByNickname: null,
    placeName: "성수동 카페",
    address: "서울 성동구 성수동 1가",
    latitude: 37.5,
    longitude: 127.0,
    instagramUrl: null,
    memo: null,
    memoSource: null,
    tag: "WISH" as PinTag,
    createdAt: "2026-05-23T00:00:00Z",
    visitedAt: null,
    memoUpdatedBy: null,
    memoUpdatedByNickname: null,
    ...overrides,
  };
}

describe("VisitToast", () => {
  it("장소명, 주소, 두 버튼을 렌더링한다", () => {
    const pin = makePin();
    render(<VisitToast pin={pin} onSkip={vi.fn()} onConfirm={vi.fn()} />);

    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(screen.getByText(/함께 방문하셨나요/)).toBeInTheDocument();
    expect(screen.getByText("성수동 카페")).toBeInTheDocument();
    expect(screen.getByText("서울 성동구 성수동 1가")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "나중에요" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /네, 다녀왔어요/ }),
    ).toBeInTheDocument();
  });

  it("주소가 null 이면 주소 줄을 렌더링하지 않는다", () => {
    const pin = makePin({ address: null });
    render(<VisitToast pin={pin} onSkip={vi.fn()} onConfirm={vi.fn()} />);
    expect(screen.queryByText(/성동구/)).not.toBeInTheDocument();
  });

  it("'나중에요' 클릭 시 onSkip 만 호출된다", () => {
    const onSkip = vi.fn();
    const onConfirm = vi.fn();
    render(
      <VisitToast pin={makePin()} onSkip={onSkip} onConfirm={onConfirm} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "나중에요" }));
    expect(onSkip).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("'네, 다녀왔어요' 클릭 시 onConfirm 만 호출된다", () => {
    const onSkip = vi.fn();
    const onConfirm = vi.fn();
    render(
      <VisitToast pin={makePin()} onSkip={onSkip} onConfirm={onConfirm} />,
    );
    fireEvent.click(screen.getByRole("button", { name: /네, 다녀왔어요/ }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onSkip).not.toHaveBeenCalled();
  });
});
