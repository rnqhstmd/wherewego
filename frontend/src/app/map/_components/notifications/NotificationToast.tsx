"use client";

import { useEffect, useRef, type CSSProperties } from "react";
import type { NotificationToastPayload } from "@/lib/notifications/types";

interface NotificationToastProps {
  payload: NotificationToastPayload;
  onDismiss: () => void;
}

/**
 * 신규 알림 도착 시 짧게 보여주는 토스트 (옵션 B, 2026-05-21).
 *
 * <p>자동 닫힘 타이머는 부모(`useNotifications`)가 관리하며,
 * 본 컴포넌트는 외부 mousedown/touchstart 감지만 담당한다.</p>
 */
export function NotificationToast({
  payload,
  onDismiss,
}: NotificationToastProps) {
  const ref = useRef<HTMLDivElement>(null);

  // 외부 탭 감지 — MobileTopNav와 동일 패턴(mousedown + touchstart).
  useEffect(() => {
    function handleOutsideTap(event: MouseEvent | TouchEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) {
        onDismiss();
      }
    }
    document.addEventListener("mousedown", handleOutsideTap);
    document.addEventListener("touchstart", handleOutsideTap);
    return () => {
      document.removeEventListener("mousedown", handleOutsideTap);
      document.removeEventListener("touchstart", handleOutsideTap);
    };
  }, [onDismiss]);

  const message =
    payload.totalPinCount <= 1
      ? `${payload.registeredByNickname}님이 ${payload.firstPlaceName}을 저장했어요`
      : `${payload.registeredByNickname}님이 ${payload.firstPlaceName} 외 ${payload.totalPinCount - 1}곳을 저장했어요`;

  const containerStyle: CSSProperties = {
    position: "fixed",
    top: 60,
    left: "50%",
    transform: "translateX(-50%)",
    whiteSpace: "nowrap",
    zIndex: 60,
  };

  const bubbleStyle: CSSProperties = {
    background: "#FFFFFF",
    borderRadius: 999,
    padding: "8px 16px",
    boxShadow: "0 2px 10px rgba(0,0,0,0.10)",
    border: "1px solid rgba(0,0,0,0.06)",
    fontSize: 12,
    color: "#333",
  };

  return (
    <div ref={ref} style={containerStyle}>
      <div style={bubbleStyle}>{message}</div>
    </div>
  );
}
