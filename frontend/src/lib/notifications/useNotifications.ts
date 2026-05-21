'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchNotifications,
  fetchNotificationDetail,
  markAllNotificationsRead,
  NOTIFICATION_SSE_URL,
} from './api';
import { createNotificationSseClient } from './sseClient';
import type {
  ConnectionState,
  NotificationDetail,
  NotificationItem,
  NotificationStreamEvent,
} from './types';

const MAX_ITEMS = 50;
const TOAST_DURATION_MS = 5_000;

export interface UseNotificationsState {
  items: NotificationItem[];
  unreadCount: number;
  connectionState: ConnectionState;
  toast: { id: number; payload: NotificationStreamEvent } | null;
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

export function useNotifications(): UseNotificationsState & UseNotificationsActions {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unreadCount, setUnreadCount] = useState<number>(0);
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting');
  const [toast, setToast] = useState<{ id: number; payload: NotificationStreamEvent } | null>(null);
  const [isPanelOpen, setIsPanelOpen] = useState<boolean>(false);

  const shownToastIds = useRef<Set<number>>(new Set());
  const isPanelOpenRef = useRef<boolean>(false);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Sync ref with state for use in SSE callback
  useEffect(() => {
    isPanelOpenRef.current = isPanelOpen;
  }, [isPanelOpen]);

  // 초기 fetch
  const refreshList = useCallback(async () => {
    try {
      const res = await fetchNotifications();
      setItems(res.items.slice(0, MAX_ITEMS));
      setUnreadCount(res.unreadCount);
    } catch (e) {
      // silent fail (네트워크 일시 끊김 등)
    }
  }, []);

  const markAllRead = useCallback(async () => {
    try {
      await markAllNotificationsRead();
      setUnreadCount(0);
      setItems((prev) =>
        prev.map((it) => (it.readAt ? it : { ...it, readAt: new Date().toISOString() })),
      );
    } catch (e) {
      // silent fail
    }
  }, []);

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

  // 마운트 시 초기 로드
  useEffect(() => {
    refreshList();
  }, [refreshList]);

  // SSE 구독
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const client = createNotificationSseClient({
      url: NOTIFICATION_SSE_URL,
      onStateChange: setConnectionState,
      onNotification: (payload) => {
        // items prepend (50건 cap)
        setItems((prev) => {
          const newItem: NotificationItem = {
            id: payload.id,
            type: payload.type,
            registeredBy: null, // SSE payload에는 없음. detail에서 채워짐
            registeredByNickname: payload.registeredByNickname,
            firstPlaceName: payload.firstPlaceName,
            totalPinCount: payload.totalPinCount,
            createdAt: payload.createdAt,
            readAt: null,
          };
          // 중복 id 차단 (재연결 시 동일 알림 재수신 가능)
          if (prev.some((it) => it.id === payload.id)) {
            return prev;
          }
          return [newItem, ...prev].slice(0, MAX_ITEMS);
        });
        setUnreadCount((prev) => prev + 1);

        // AC-16: 동일 알림은 어떤 경로로든 한 번만 toast 노출 대상으로 등록한다.
        //        패널 열림 상태에서도 shownToastIds 에 기록하여, 재연결로 동일 알림이
        //        다시 수신되었을 때 패널 닫힌 경로에서 toast 가 재노출되는 것을 차단한다.
        if (shownToastIds.current.has(payload.id)) {
          // 이미 처리된 알림: toast 미노출. 단 패널 열림이면 read-all 재호출 (AC-17).
          if (isPanelOpenRef.current) {
            markAllRead();
          }
          return;
        }
        shownToastIds.current.add(payload.id);

        if (isPanelOpenRef.current) {
          // 패널 열림: toast 미노출, 즉시 read-all 재호출 (AC-17)
          markAllRead();
        } else {
          // 패널 닫힘: toast 노출 + 5초 자동 닫힘 (FR-15)
          if (toastTimerRef.current) {
            clearTimeout(toastTimerRef.current);
          }
          setToast({ id: payload.id, payload });
          toastTimerRef.current = setTimeout(() => {
            setToast(null);
            toastTimerRef.current = null;
          }, TOAST_DURATION_MS);
        }
      },
    });

    client.start();

    return () => {
      client.stop();
      if (toastTimerRef.current) {
        clearTimeout(toastTimerRef.current);
        toastTimerRef.current = null;
      }
    };
  }, [markAllRead]);

  return {
    items,
    unreadCount,
    connectionState,
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
