import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import RouletteResultContent from "./RouletteResultContent";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

function makePin(overrides: Partial<PinSummaryResponse> = {}): PinSummaryResponse {
  return {
    id: 1,
    groupId: 1,
    createdBy: 1,
    createdByNickname: null,
    placeName: "테스트 장소",
    address: "서울 강남구",
    latitude: 37.5,
    longitude: 127.0,
    instagramUrl: null,
    memo: null,
    memoSource: null,
    tag: "REEL" as PinTag,
    createdAt: "2025-01-01T00:00:00Z",
    visitedAt: null,
    memoUpdatedBy: null,
    memoUpdatedByNickname: null,
    wantCount: 0,
    myWant: false,
    ...overrides,
  };
}

describe("RouletteResultContent", () => {
  it("(AC-18) distanceKm=0.8 → '800m', 1.5 → '1.5km' 렌더", () => {
    const pin = makePin();
    const baseProps = {
      pin,
      onShowOnMap: vi.fn(),
      onReRoll: vi.fn(),
    };
    const { rerender } = render(
      <RouletteResultContent {...baseProps} distanceKm={0.8} />,
    );
    expect(screen.getByText(/800\s*m/)).toBeInTheDocument();
    rerender(<RouletteResultContent {...baseProps} distanceKm={1.5} />);
    expect(screen.getByText(/1\.5\s*km/)).toBeInTheDocument();
  });
});
