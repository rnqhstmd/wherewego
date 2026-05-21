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

function triggerFocus() {
  window.dispatchEvent(new Event("focus"));
}

/**
 * 다음 mockResolvedValueOnce가 외부에서 resolve될 수 있도록 deferred Promise를 반환.
 * race condition / out-of-order 응답 시나리오를 만들 때 사용한다.
 */
function deferredFetch<T>() {
  let resolveFn!: (value: T) => void;
  const promise = new Promise<T>((resolve) => {
    resolveFn = resolve;
  });
  return { promise, resolve: resolveFn };
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

  it("(W3) focus 이벤트 트리거 fetch → 신규 알림 토스트 노출", async () => {
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 10 })],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
    });

    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 25, firstPlaceName: "블루보틀 청담점", registeredByNickname: "carol" }), makeItem({ id: 10 })],
      unreadCount: 1,
    });

    await act(async () => {
      triggerFocus();
    });

    await waitFor(() => {
      expect(result.current.toast?.id).toBe(25);
    });
    expect(result.current.items[0]?.id).toBe(25);
  });

  it("(W1) out-of-order 응답 폐기 — 늦게 도착한 stale 응답이 최신 state를 덮지 않음", async () => {
    // 첫 fetch (mount): id=5
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 5 })],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
    });

    // 두 개의 deferred fetch 준비 — 두 번째가 먼저 resolve되도록.
    const first = deferredFetch<{ items: NotificationItem[]; unreadCount: number }>();
    const second = deferredFetch<{ items: NotificationItem[]; unreadCount: number }>();
    mockedFetchNotifications.mockReturnValueOnce(first.promise);
    mockedFetchNotifications.mockReturnValueOnce(second.promise);

    // 두 트리거 발사 (visibility → focus)
    await act(async () => {
      triggerVisibilityVisible();
    });
    await act(async () => {
      triggerFocus();
    });

    // 두 번째(최신) 요청부터 resolve — 새 id=99
    await act(async () => {
      second.resolve({
        items: [makeItem({ id: 99 }), makeItem({ id: 5 })],
        unreadCount: 1,
      });
    });

    await waitFor(() => {
      expect(result.current.items[0]?.id).toBe(99);
    });
    expect(result.current.toast?.id).toBe(99);

    // 이제 stale한 첫 번째 요청을 늦게 resolve — id=99가 사라진 옛 상태.
    // requestSeqRef 가드에 의해 state도 lastSeenMaxIdRef도 덮어쓰지 않아야 함.
    await act(async () => {
      first.resolve({
        items: [makeItem({ id: 5 })],
        unreadCount: 0,
      });
    });

    // items가 옛 상태로 되돌아가지 않음
    expect(result.current.items[0]?.id).toBe(99);
    expect(result.current.unreadCount).toBe(1);
  });

  it("(I4) fetchNotifications reject 시 silent fail — 기존 상태 유지", async () => {
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 10 })],
      unreadCount: 2,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
      expect(result.current.unreadCount).toBe(2);
    });

    // 다음 fetch 실패
    mockedFetchNotifications.mockRejectedValueOnce(new Error("network down"));

    await act(async () => {
      triggerVisibilityVisible();
    });

    // 기존 state 유지, 에러 throw 안 됨
    await waitFor(() => {
      expect(mockedFetchNotifications).toHaveBeenCalledTimes(2);
    });
    expect(result.current.items[0]?.id).toBe(10);
    expect(result.current.unreadCount).toBe(2);
    expect(result.current.toast).toBeNull();
  });

  it("(I4) 패널 close → reopen 시 이미 본 알림은 토스트 재노출 안 함", async () => {
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 10 })],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
    });

    // 신규 알림 도착 → 토스트 (panel closed)
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 50 }), makeItem({ id: 10 })],
      unreadCount: 1,
    });
    await act(async () => {
      triggerVisibilityVisible();
    });
    await waitFor(() => {
      expect(result.current.toast?.id).toBe(50);
    });

    // 패널 열기 → 닫기
    await act(async () => {
      await result.current.openPanel();
    });
    act(() => {
      result.current.closePanel();
    });
    expect(result.current.isPanelOpen).toBe(false);

    // 토스트 dismiss (시뮬레이션)
    act(() => {
      result.current.dismissToast();
    });

    // 같은 알림 목록 (max id는 그대로 50) 다시 fetch
    mockedFetchNotifications.mockResolvedValueOnce({
      items: [makeItem({ id: 50 }), makeItem({ id: 10 })],
      unreadCount: 0,
    });
    await act(async () => {
      triggerVisibilityVisible();
    });

    // newMaxId === prevMaxId → 토스트 미노출
    // mount(1) + 첫 visibility(2) + 두 번째 visibility(3) = 3회
    await waitFor(() => {
      expect(mockedFetchNotifications).toHaveBeenCalledTimes(3);
    });
    expect(result.current.toast).toBeNull();
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
