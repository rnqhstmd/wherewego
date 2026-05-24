import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import VisitMemoSheet from "./VisitMemoSheet";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

function makePin(overrides: Partial<PinSummaryResponse> = {}): PinSummaryResponse {
  return {
    id: 1,
    groupId: 1,
    createdBy: 1,
    createdByNickname: null,
    placeName: "성수동 카페",
    address: null,
    latitude: 37.5,
    longitude: 127.0,
    instagramUrl: null,
    memo: null,
    memoSource: null,
    tag: "MEMORY" as PinTag,
    createdAt: "2026-05-23T00:00:00Z",
    visitedAt: null,
    memoUpdatedBy: null,
    memoUpdatedByNickname: null,
    ...overrides,
  };
}

const VISITED_AT = new Date(2026, 4, 23); // 2026-05-23 (Date 생성자: month 0-index → 4 = 5월)

describe("VisitMemoSheet", () => {
  it("장소명 헤더와 방문 날짜를 렌더링한다", () => {
    render(
      <VisitMemoSheet
        pin={makePin()}
        visitedAt={VISITED_AT}
        onSave={vi.fn().mockResolvedValue({ ok: true })}
        onSkip={vi.fn()}
      />,
    );
    expect(
      screen.getByText(/성수동 카페, 다녀온 흔적을 남겨볼까요/),
    ).toBeInTheDocument();
    expect(screen.getByText("다녀온 날 · 2026.05.23")).toBeInTheDocument();
  });

  it("저장 성공 시 onSave 가 입력값으로 호출되고 에러는 노출되지 않는다", async () => {
    const onSave = vi.fn().mockResolvedValue({ ok: true });
    render(
      <VisitMemoSheet
        pin={makePin()}
        visitedAt={VISITED_AT}
        onSave={onSave}
        onSkip={vi.fn()}
      />,
    );
    const textarea = screen.getByPlaceholderText(
      /오늘의 순간을 짧게 남겨두세요/,
    ) as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: "달콤한 휘낭시에" } });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith("달콤한 휘낭시에"),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("저장 실패 시 인라인 에러를 노출하고 시트는 유지된다 (FR-VD-22)", async () => {
    const onSave = vi
      .fn()
      .mockResolvedValue({ ok: false, message: "메모가 너무 길어요" });
    render(
      <VisitMemoSheet
        pin={makePin()}
        visitedAt={VISITED_AT}
        onSave={onSave}
        onSkip={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(
        "메모가 너무 길어요",
      ),
    );
    // 저장 버튼이 다시 활성화되어 재시도 가능 — 시트가 유지된다는 시그널.
    expect(
      (screen.getByRole("button", { name: "저장" }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
  });

  it("건너뛰기 클릭 시 onSkip 만 호출되고 onSave 는 호출되지 않는다 (FR-VD-20)", () => {
    const onSave = vi.fn().mockResolvedValue({ ok: true });
    const onSkip = vi.fn();
    render(
      <VisitMemoSheet
        pin={makePin()}
        visitedAt={VISITED_AT}
        onSave={onSave}
        onSkip={onSkip}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "건너뛰기" }));
    expect(onSkip).toHaveBeenCalledTimes(1);
    expect(onSave).not.toHaveBeenCalled();
  });
});
