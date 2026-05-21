'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchNotifications,
  fetchNotificationDetail,
  markAllNotificationsRead,
} from './api';
import type {
  NotificationDetail,
  NotificationItem,
  NotificationToastPayload,
} from './types';

const MAX_ITEMS = 50;
const TOAST_DURATION_MS = 5_000;

export interface UseNotificationsState {
  items: NotificationItem[];
  unreadCount: number;
  toast: { id: number; payload: NotificationToastPayload } | null;
  isPanelOpen: boolean;
}

export interface UseNotificationsActions {
  openPanel: () => Promise<void>;
  closePanel: () => void;
  markAllRead: () => Promise<void>;
  refreshList: () => Promise<void>;
  dismissToast: () => void;
  loadDetail: (notificationId: number) => Promise<NotificationDetail>;
}

/**
 * 인앱 알림함 상태/액션 훅 (옵션 B, 2026-05-21).
 *
 * <p>실시간 push(SSE) 없이 mount + Page Visibility(`visibilitychange` hidden→visible)
 * + window `focus` 이벤트에서 최근 알림 목록을 재조회한다. 직전 max id를 초과한
 * 최상위 신규 알림 1건만 토스트로 노출한다 (FR-15 변형, AC-16 dedup 유지).</p>
 */
export function useNotifications(): UseNotificationsState & UseNotificationsActions {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unreadCount, setUnreadCount] = useState<number>(0);
  const [toast, setToast] = useState<{ id: number; payload: NotificationToastPayload } | null>(null);
  const [isPanelOpen, setIsPanelOpen] = useState<boolean>(false);

  const lastSeenMaxIdRef = useRef<number>(0);
  const shownToastIdsRef = useRef<Set<number>>(new Set());
  const isPanelOpenRef = useRef<boolean>(false);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMountedRef = useRef<boolean>(true);
  const isFirstFetchRef = useRef<boolean>(true);

  useEffect(() => {
    isPanelOpenRef.current = isPanelOpen;
  }, [isPanelOpen]);

  const markAllRead = useCallback(async () => {
    try {
      await markAllNotificationsRead();
      if (!isMountedRef.current) return;
      setUnreadCount(0);
      setItems((prev) =>
        prev.map((it) => (it.readAt ? it : { ...it, readAt: new Date().toISOString() })),
      );
    } catch {
      // silent fail
    }
  }, []);

  const showToast = useCallback((item: NotificationItem) => {
    if (shownToastIdsRef.current.has(item.id)) return;
    shownToastIdsRef.current.add(item.id);

    if (isPanelOpenRef.current) {
      // 패널 열림: 토스트 미노출, 읽음 처리 갱신 (AC-17 유사 — 트리거가 fetch 결과로 변경됨)
      markAllRead();
      return;
    }

    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
    }
    const payload: NotificationToastPayload = {
      id: item.id,
      type: item.type,
      registeredByNickname: item.registeredByNickname,
      firstPlaceName: item.firstPlaceName,
      totalPinCount: item.totalPinCount,
      createdAt: item.createdAt,
    };
    setToast({ id: item.id, payload });
    toastTimerRef.current = setTimeout(() => {
      setToast(null);
      toastTimerRef.current = null;
    }, TOAST_DURATION_MS);
  }, [markAllRead]);

  /**
   * 최근 알림 목록을 재조회한다. 첫 fetch가 아니면 직전 max id를 초과한
   * 최상위 신규 알림 1건을 토스트로 노출한다.
   */
  const refreshList = useCallback(async () => {
    try {
      const res = await fetchNotifications();
      if (!isMountedRef.current) return;

      const next = res.items.slice(0, MAX_ITEMS);
      setItems(next);
      setUnreadCount(res.unreadCount);

      const newMaxId = next.length > 0 ? next[0].id : 0;
      const prevMaxId = lastSeenMaxIdRef.current;

      if (isFirstFetchRef.current) {
        // 마운트 첫 fetch: 초기화만, 토스트 노출 없음.
        lastSeenMaxIdRef.current = newMaxId;
        isFirstFetchRef.current = false;
        return;
      }

      if (newMaxId > prevMaxId) {
        // 직전 max id를 초과한 최상위 신규 알림 1건만 토스트 (간소화).
        const topNew = next[0];
        if (topNew) {
          showToast(topNew);
        }
      }
      lastSeenMaxIdRef.current = Math.max(prevMaxId, newMaxId);
    } catch {
      // silent fail (네트워크 일시 끊김 등)
    }
  }, [showToast]);

  const openPanel = useCallback(async () => {
    setIsPanelOpen(true);
    await markAllRead();
  }, [markAllRead]);

  const closePanel = useCallback(() => {
    setIsPanelOpen(false);
  }, []);

  const dismissToast = useCallback(() => {
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
      toastTimerRef.current = null;
    }
    setToast(null);
  }, []);

  const loadDetail = useCallback((notificationId: number) => {
    return fetchNotificationDetail(notificationId);
  }, []);

  // 마운트 + visibility/focus 트리거 fetch
  useEffect(() => {
    if (typeof window === 'undefined') return;

    isMountedRef.current = true;
    // mount 시 초기 fetch — refreshList는 setState를 호출하지만 부수효과(API call) 동기화 목적이므로 의도된 패턴.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refreshList();

    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        refreshList();
      }
    };
    const onFocus = () => {
      refreshList();
    };

    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('focus', onFocus);

    return () => {
      isMountedRef.current = false;
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('focus', onFocus);
      if (toastTimerRef.current) {
        clearTimeout(toastTimerRef.current);
        toastTimerRef.current = null;
      }
    };
  }, [refreshList]);

  return {
    items,
    unreadCount,
    toast,
    isPanelOpen,
    openPanel,
    closePanel,
    markAllRead,
    refreshList,
    dismissToast,
    loadDetail,
  };
}
