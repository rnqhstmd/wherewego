import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";

import type { InviteLinkPreviewResponse } from "@/lib/api/group-client";

// appStore 모듈을 모킹 — IOS_APP_URL 만 export (isAppStoreReady 제거됨).
vi.mock("@/lib/config/appStore", () => ({
  IOS_APP_URL: "https://apps.apple.com/app/wherewego/id000000000",
}));

// acceptInviteLink 가 import/호출되지 않음을 확인하기 위한 스파이.
// (호출되면 테스트가 깨지도록 throw)
const acceptInviteLinkSpy = vi.fn(() => {
  throw new Error("acceptInviteLink must not be called (web join removed)");
});
vi.mock("@/lib/api/group-client", () => ({
  acceptInviteLink: acceptInviteLinkSpy,
}));

import { InvitePreviewClient } from "./InvitePreviewClient";

function makePreview(
  overrides: Partial<InviteLinkPreviewResponse> = {},
): InviteLinkPreviewResponse {
  return {
    token: "tok-123",
    groupName: "테스트 그룹",
    inviterNickname: "초대자",
    // 충분히 먼 미래 — 카운트다운이 "남음" 으로 표시되도록.
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    ...overrides,
  };
}

describe("InvitePreviewClient", () => {
  beforeEach(() => {
    acceptInviteLinkSpy.mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("(AC-1) slug 코드를 화면에 표시한다", () => {
    render(<InvitePreviewClient slug="ABCD2345" preview={makePreview()} />);
    expect(screen.getByText("ABCD2345")).toBeInTheDocument();
  });

  it("(AC-2) '코드 복사' 클릭 → navigator.clipboard.writeText(slug) 호출 + 라벨 토글", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    // jsdom 의 navigator.clipboard 는 non-writable getter 라 직접 할당이 무시된다.
    // defineProperty 로 configurable 하게 덮어쓴다.
    // 참고: userEvent.setup() 은 자체 clipboard stub 을 설치하므로 여기서는
    // fireEvent.click 으로 컴포넌트의 navigator.clipboard 접근을 직접 검증한다.
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });

    render(<InvitePreviewClient slug="ABCD2345" preview={makePreview()} />);

    fireEvent.click(screen.getByRole("button", { name: "코드 복사" }));

    expect(writeText).toHaveBeenCalledWith("ABCD2345");
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "복사됐어요" }),
      ).toBeInTheDocument(),
    );
  });

  it("(AC-3) App Store 배지 링크가 IOS_APP_URL 을 href 로 가진다", () => {
    render(<InvitePreviewClient slug="ABCD2345" preview={makePreview()} />);
    const badge = screen.getByAltText("App Store에서 다운로드");
    expect(badge).toBeInTheDocument();
    const link = badge.closest("a");
    expect(link).toHaveAttribute(
      "href",
      "https://apps.apple.com/app/wherewego/id000000000",
    );
  });

  it("(AC-4) acceptInviteLink 가 호출되지 않는다 (웹 수락 제거)", () => {
    render(<InvitePreviewClient slug="ABCD2345" preview={makePreview()} />);
    // "합류하기"/"취소" 버튼이 없다.
    expect(
      screen.queryByRole("button", { name: "합류하기" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "취소" }),
    ).not.toBeInTheDocument();
    expect(acceptInviteLinkSpy).not.toHaveBeenCalled();
  });
});
