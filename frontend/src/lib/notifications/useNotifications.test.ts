import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

import type {
  NotificationItem,
  NotificationStreamEvent,
} from "./types";
import type { SseClient, SseClientOptions } from "./sseClient";

// ./api 모킹
vi.mock("./api", () => ({
  fetchNotifications: vi.fn(),
  fetchNotificationDetail: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  NOTIFICATION_SSE_URL: "/api/v1/notifications/stream",
}));

// ./sseClient 모킹 — onNotification 콜백을 테스트에서 직접 트리거할 수 있도록 캡처
let capturedOptions: SseClientOptions | null = null;
const startSpy = vi.fn();
const stopSpy = vi.fn();

vi.mock("./sseClient", () => ({
  createNotificationSseClient: (options: SseClientOptions): SseClient => {
    capturedOptions = options;
    return {
      start: startSpy,
      stop: stopSpy,
    };
  },
}));

// mock 후 import (vi.mock은 hoist되지만 명시적 순서를 위해)
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

function makeStreamEvent(
  overrides: Partial<NotificationStreamEvent> = {},
): NotificationStreamEvent {
  return {
    id: 100,
    type: "MANUAL_PIN",
    registeredByNickname: "bob",
    firstPlaceName: "이디야 역삼점",
    totalPinCount: 1,
    createdAt: "2025-02-01T00:00:00Z",
    ...overrides,
  };
}

describe("useNotifications", () => {
  beforeEach(() => {
    capturedOptions = null;
    startSpy.mockClear();
    stopSpy.mockClear();
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

  it("SSE 수신 → items prepend + toast 세팅 + unreadCount 증가", async () => {
    mockedFetchNotifications.mockResolvedValue({
      items: [makeItem({ id: 1 })],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(result.current.items).toHaveLength(1);
      expect(capturedOptions).not.toBeNull();
    });

    const event = makeStreamEvent({ id: 100 });
    act(() => {
      capturedOptions!.onNotification(event);
    });

    expect(result.current.items[0]?.id).toBe(100);
    expect(result.current.items).toHaveLength(2);
    expect(result.current.unreadCount).toBe(1);
    expect(result.current.toast?.id).toBe(100);
    expect(result.current.toast?.payload).toEqual(event);
  });

  it("(AC-17) 패널 열림 중 SSE 수신 → toast 미노출 + read-all 호출", async () => {
    mockedFetchNotifications.mockResolvedValue({
      items: [],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(capturedOptions).not.toBeNull();
    });

    // 패널 열기 (이 시점에 markAllRead 1회 호출)
    await act(async () => {
      await result.current.openPanel();
    });
    expect(result.current.isPanelOpen).toBe(true);
    const callsAfterOpen = mockedMarkAllRead.mock.calls.length;

    // 패널 열린 상태에서 SSE 수신
    const event = makeStreamEvent({ id: 200 });
    await act(async () => {
      capturedOptions!.onNotification(event);
    });

    // toast 미노출
    expect(result.current.toast).toBeNull();
    // markAllRead 추가 호출됨 (AC-17)
    expect(mockedMarkAllRead.mock.calls.length).toBeGreaterThan(callsAfterOpen);
  });

  it("(AC-16) 동일 id 재수신 → toast 미노출 (재연결 시 동일 알림)", async () => {
    mockedFetchNotifications.mockResolvedValue({
      items: [],
      unreadCount: 0,
    });

    const { result } = renderHook(() => useNotifications());

    await waitFor(() => {
      expect(capturedOptions).not.toBeNull();
    });

    const event = makeStreamEvent({ id: 300 });

    // 첫 번째 수신 → toast 노출
    act(() => {
      capturedOptions!.onNotification(event);
    });
    expect(result.current.toast?.id).toBe(300);

    // toast dismiss (다음 수신 결과를 명확히 보기 위해)
    act(() => {
      result.current.dismissToast();
    });
    expect(result.current.toast).toBeNull();

    // 같은 id로 두 번째 수신 → toast 다시 노출되지 않음
    act(() => {
      capturedOptions!.onNotification(event);
    });
    expect(result.current.toast).toBeNull();
  });
});
