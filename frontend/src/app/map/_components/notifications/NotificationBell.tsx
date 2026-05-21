"use client";

// NotificationBell — 우상단/사이드바용 알림 벨 버튼.
// 미읽음 개수와 SSE 연결 상태에 따라 우상단 점(빨강/회색) 노출.

import { useState, type CSSProperties } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { IconBell } from "@/components/icons/IconBell";

type ConnectionState = "connecting" | "open" | "closed" | "failed";

interface NotificationBellProps {
  /** 미읽음 개수. 0 이상. */
  unreadCount: number;
  /** SSE 연결 상태. failed 시 회색 점으로 끊김 표시. */
  connectionState: ConnectionState;
  /** mobile(44x44) / desktop(36x36 + tooltip). 기본 mobile. */
  variant?: "mobile" | "desktop";
  onClick: () => void;
  className?: string;
}

/**
 * 알림 벨 버튼.
 *
 * - 모바일: 44x44 원형 (MobileTopNav 프로필 톤 차용 — panel 배경 + hairline + shadow)
 * - 데스크탑: 36x36 원형 (DesktopActionPill 아이콘 톤 차용 — transparent 배경 + hover tooltip)
 * - 우상단 점(8x8): 미읽음 > 0 → pinNew(빨강), 연결 failed → 회색
 * - 정상 연결 + 미읽음 0 → 점 미표시
 */
export function NotificationBell({
  unreadCount,
  connectionState,
  variant = "mobile",
  onClick,
  className,
}: NotificationBellProps) {
  const [hoveredTooltip, setHoveredTooltip] = useState(false);

  const isDesktop = variant === "desktop";
  const isFailed = connectionState === "failed";
  const hasUnread = unreadCount > 0;
  // 빨강은 unread > 0 AND failed 아닐 때만. failed 시 회색 우선.
  const showDot = isFailed || hasUnread;
  const dotColor = isFailed ? "rgba(120,120,120,0.9)" : colors.pinNew;
  const tooltipText = isFailed
    ? "알림 연결이 끊겼어요. 새로고침해 주세요"
    : "알림";

  const size = isDesktop ? 36 : 44;
  const iconSize = isDesktop ? 20 : 22;

  // 모바일은 MobileTopNav 프로필과 동일한 톤(bg + hairline + shadow),
  // 데스크탑은 DesktopActionPill 내부에 들어가므로 transparent 배경 + hover.
  const buttonStyle: CSSProperties = isDesktop
    ? {
        position: "relative",
        width: size,
        height: size,
        borderRadius: "50%",
        border: "none",
        background: "transparent",
        color: colors.inkSoft,
        cursor: "pointer",
        padding: 0,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        transition: "background 160ms ease-out, color 160ms ease-out",
      }
    : {
        position: "relative",
        width: size,
        height: size,
        borderRadius: "50%",
        border: `1px solid ${colors.hairline}`,
        background: colors.bg,
        color: colors.inkSoft,
        boxShadow: `0 6px 18px ${colors.shadow}`,
        cursor: "pointer",
        padding: 0,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
      };

  const dotStyle: CSSProperties = {
    position: "absolute",
    top: isDesktop ? 6 : 8,
    right: isDesktop ? 6 : 8,
    width: 8,
    height: 8,
    borderRadius: "50%",
    background: dotColor,
    border: `1.5px solid ${colors.panel}`,
    pointerEvents: "none",
  };

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={tooltipText}
      title={isDesktop ? undefined : tooltipText}
      className={className}
      style={buttonStyle}
      onMouseEnter={(e) => {
        if (isDesktop) {
          setHoveredTooltip(true);
          e.currentTarget.style.background = `${colors.cta}12`;
          e.currentTarget.style.color = colors.cta;
        }
      }}
      onMouseLeave={(e) => {
        if (isDesktop) {
          setHoveredTooltip(false);
          e.currentTarget.style.background = "transparent";
          e.currentTarget.style.color = colors.inkSoft;
        }
      }}
    >
      <IconBell size={iconSize} color="currentColor" />
      {showDot && <span style={dotStyle} aria-hidden="true" />}

      {isDesktop && hoveredTooltip && (
        <span
          role="tooltip"
          style={{
            position: "absolute",
            left: size + 12,
            top: "50%",
            transform: "translateY(-50%)",
            background: colors.ink,
            color: "#FFFFFF",
            fontFamily: fonts.sans,
            fontSize: 12,
            fontWeight: 600,
            letterSpacing: -0.2,
            padding: "8px 10px",
            lineHeight: 1,
            borderRadius: 8,
            whiteSpace: "nowrap",
            pointerEvents: "none",
            boxShadow: `0 6px 16px ${colors.shadow}`,
            animation:
              "maygo-bubble-pop 180ms cubic-bezier(0.2,0.8,0.2,1) both",
          }}
        >
          {tooltipText}
        </span>
      )}
    </button>
  );
}
