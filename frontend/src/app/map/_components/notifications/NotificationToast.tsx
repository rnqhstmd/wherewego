"use client";

import { useEffect, useRef, type CSSProperties } from "react";
import type { NotificationStreamEvent } from "@/lib/notifications/types";

interface NotificationToastProps {
  payload: NotificationStreamEvent;
  onDismiss: () => void;
  /** 모바일은 벨(우상단) 아래, 데스크탑은 사이드바 우측 위치. 기본 mobile. */
  anchorVariant?: "mobile" | "desktop";
}

/**
 * SSE로 도착한 신규 알림을 짧게 보여주는 토스트.
 *
 * <p>자동 닫힘 타이머는 부모(`useNotifications`)가 관리하며,
 * 본 컴포넌트는 외부 mousedown/touchstart 감지만 담당한다.</p>
 */
export function NotificationToast({
  payload,
  onDismiss,
  anchorVariant = "mobile",
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

  const containerStyle: CSSProperties =
    anchorVariant === "mobile"
      ? { position: "fixed", top: 64, right: 14, zIndex: 60 }
      : { position: "fixed", top: 80, left: 80, zIndex: 60 };

  const bubbleStyle: CSSProperties = {
    background: "#FFFFFF",
    borderRadius: 12,
    padding: "10px 14px",
    boxShadow: "0 4px 14px rgba(0,0,0,0.12)",
    border: "1px solid rgba(0,0,0,0.06)",
    fontSize: 13,
    color: "#333",
    maxWidth: 280,
    lineHeight: 1.45,
  };

  return (
    <div ref={ref} style={containerStyle}>
      <div style={bubbleStyle}>{message}</div>
    </div>
  );
}
