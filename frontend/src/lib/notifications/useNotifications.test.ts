import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

import type { NotificationItem } from "./types";

// ./api 모킹
vi.mock("./api", () => ({
  fetchNotifications: vi.fn(),
  fetchNotificationDetail: vi.fn(),
  markAllNotificationsRead: vi.fn(),
}));

// mock 후 import
import { useNotifications } from "./useNotifications";
import {
  fetchNotifications,
  markAllNotificationsRead,
} from "./api";

const mockedFetchNotifications = vi.mocked(fetchNotifications);
const mockedMarkAllRead = vi.mocked(markAllNotificationsRead);

function makeItem(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: 1,
    type: "MANUAL_PIN",
    registeredBy: 10,
    registeredByNickname: "alice",
    firstPlaceName: "스타벅스 강남점",
    totalPinCount: 1,
    createdAt: "2025-01-01T00:00:00Z",
    readAt: null,
    ...overrides,
  };
}

function triggerVisibilityVisible() {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => "visible",
  });
  document.dispatchEvent(new Event("visibilitychange"));
}

describe("useNotifications", () => {
  beforeEach(() => {
    mockedFetchNotifications.mockReset();
    mockedMarkAllRead.mockReset();
    mockedMarkAllRead.mockResolvedValue({ updatedCount: 0 });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("mount 시 fetchNotifications 호출 → items, unreadCount 설정", async () => {
    const initialItems = [makeItem({ id: 1 }), makeItem({ id: 2 })];
    mockedFetchNotifications.mockResolvedValue({
      items: initialItems,
      unreadCount: 3,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(mockedFetchNotifications).toHaveBeenCalled();
      expect(result.current.items).toHaveLength(2);
      expect(result.current.unreadCount).toBe(3);
    });
  });

  it("(FR-15 옵션 B) visibilitychange 트리거 fetch → 직전 max id 초과한 신규 알림 1건 토스트 노출", async () => {
    // 첫 fetch: 기존 알림 id=10
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 10 })],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
    });

    // 첫 fetch는 토스트 미노출
    expect(result.current.toast).toBeNull();

    // 두 번째 fetch: 신규 알림 id=20 도착
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 20, firstPlaceName: "이디야 역삼점", registeredByNickname: "bob" }), makeItem({ id: 10 })],
      unreadCount: 1,
    });

    await act(async () => {
      triggerVisibilityVisible();
    });

    await waitFor(() => {
      expect(result.current.toast?.id).toBe(20);
    });
    expect(result.current.items[0]?.id).toBe(20);
    expect(result.current.unreadCount).toBe(1);
  });

  it("(AC-17) 패널 열림 중 visibility fetch로 신규 알림 도착 → 토스트 미노출 + read-all 호출", async () => {
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 10 })],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
    });

    // 패널 열기 (이 시점에 markAllRead 1회 호출)
    await act(async () => {
      await result.current.openPanel();
    });
    expect(result.current.isPanelOpen).toBe(true);
    const callsAfterOpen = mockedMarkAllRead.mock.calls.length;

    // 패널 열린 상태에서 visibility 트리거 + 신규 알림
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 30 }), makeItem({ id: 10 })],
      unreadCount: 1,
    });

    await act(async () => {
      triggerVisibilityVisible();
    });

    await waitFor(() => {
      expect(result.current.items[0]?.id).toBe(30);
    });

    // 토스트 미노출
    expect(result.current.toast).toBeNull();
    // markAllRead 추가 호출 (AC-17)
    expect(mockedMarkAllRead.mock.calls.length).toBeGreaterThan(callsAfterOpen);
  });

  it("(AC-16) 같은 id가 재차 최상위로 들어와도 토스트 중복 노출 안 함", async () => {
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 100 })],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
    });

    // 신규 알림 id=200 도착 → 토스트 노출
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 200 }), makeItem({ id: 100 })],
      unreadCount: 1,
    });
    await act(async () => {
      triggerVisibilityVisible();
    });
    await waitFor(() => {
      expect(result.current.toast?.id).toBe(200);
    });

    // 토스트 직접 닫음
    act(() => {
      result.current.dismissToast();
    });
    expect(result.current.toast).toBeNull();

    // 같은 id=200이 다시 최상위(목록은 동일)인 상태에서 visibility 트리거.
    // max id가 갱신되어 prevMaxId == newMaxId 이므로 토스트 노출되지 않아야 함.
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 200 }), makeItem({ id: 100 })],
      unreadCount: 1,
    });
    await act(async () => {
      triggerVisibilityVisible();
    });

    expect(result.current.toast).toBeNull();
  });
});
