"use client";

import { useEffect } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface WishToastProps {
  placeName: string;
  onDismiss: () => void;
  /** 자동 닫힘 ms. 기본 3500ms. */
  durationMs?: number;
}

/**
 * Phase 12 후속(UX 재반영): WISH 자동 전환 토스트.
 * 사용자가 마지막 WANT 를 눌러 그룹 과반에 도달했을 때 상단 중앙에 3.5초 잠깐 노출된다.
 * 인앱 알림(WISH_CONVERTED bell)과 별개로, 행동 직후 즉시 피드백 제공이 목적.
 */
export default function WishToast({
  placeName,
  onDismiss,
  durationMs = 3500,
}: WishToastProps) {
  useEffect(() => {
    const t = window.setTimeout(onDismiss, durationMs);
    return () => window.clearTimeout(t);
  }, [onDismiss, durationMs]);

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: "fixed",
        top: 16,
        left: "50%",
        transform: "translateX(-50%)",
        width: "calc(100% - 24px)",
        maxWidth: 360,
        zIndex: 30,
        background: colors.panel,
        borderRadius: 14,
        padding: "12px 16px",
        boxShadow: `0 10px 28px ${colors.shadowMd}`,
        border: `1px solid ${colors.hairline}`,
        fontFamily: fonts.sans,
        display: "flex",
        alignItems: "center",
        gap: 12,
        animation: "maygo-wish-toast-in 220ms cubic-bezier(0.2,0.8,0.2,1) both",
      }}
    >
      <span
        aria-hidden="true"
        style={{
          width: 32,
          height: 32,
          borderRadius: "50%",
          background: `${colors.pinWish}26`,
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
          fontSize: 18,
        }}
      >
        🌟
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{
            fontSize: 13.5,
            fontWeight: 700,
            color: colors.ink,
            lineHeight: 1.3,
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          위시로 올라갔어요!
        </div>
        <div
          style={{
            fontSize: 12,
            color: colors.inkSoft,
            lineHeight: 1.35,
            marginTop: 2,
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          ‘{placeName}’ 둘 다 가고 싶어해요
        </div>
      </div>
      <button
        type="button"
        onClick={onDismiss}
        aria-label="닫기"
        style={{
          background: "transparent",
          border: "none",
          padding: 4,
          cursor: "pointer",
          color: colors.inkSoft,
          fontSize: 18,
          lineHeight: 1,
        }}
      >
        ×
      </button>
    </div>
  );
}
