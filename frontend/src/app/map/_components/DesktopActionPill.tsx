"use client";

import { useState } from "react";
import Link from "next/link";
import { colors, fonts } from "@/lib/design/tokens";
import { IconSearch, IconPlus, IconShuffle } from "@/components/icons";
import type { ActionBarTab } from "./types";

interface DesktopActionPillProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
  /** 셔플 탭만 비활성화 (위치 권한 거부 등). */
  rouletteDisabled?: boolean;
  /** 사이드바 하단 프로필 아바타에 노출할 닉네임 첫 글자. */
  myNickname?: string;
  /** 하단 프로필 위에 노출할 알림 벨 (desktop variant, 36x36). */
  notificationBell?: React.ReactNode;
}

const TABS: Array<{
  id: Exclude<ActionBarTab, null>;
  Icon: typeof IconSearch;
  label: string;
}> = [
  { id: "search", Icon: IconSearch, label: "장소 검색" },
  { id: "add", Icon: IconPlus, label: "장소 추가" },
  { id: "roulette", Icon: IconShuffle, label: "오늘 어디 갈까?" },
];

/**
 * 데스크탑 좌측 세로 floating 사이드바.
 *
 * - 좌상단 로고와 중심 정렬 (둘 다 left:14, 컬럼 너비 44)
 * - 상단에 액션 아이콘 3개(검색/추가/룰렛) 세로 배치
 * - 하단에 마이페이지 진입 아바타 (그라데이션 + 닉네임 첫 글자)
 * - 호버 시 우측에 라벨 툴팁 노출 (데스크탑 한정)
 */
export default function DesktopActionPill({
  active,
  onChange,
  rouletteDisabled = false,
  myNickname,
  notificationBell,
}: DesktopActionPillProps) {
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const initial = myNickname?.trim().charAt(0) ?? "";

  return (
    <div
      style={{
        position: "absolute",
        top: 72,
        bottom: 14,
        left: 14,
        width: 44,
        background: colors.panel,
        border: `1px solid ${colors.hairline}`,
        borderRadius: 22,
        boxShadow: `0 10px 28px ${colors.shadowMd}`,
        padding: "8px 4px",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 6,
        zIndex: 24,
      }}
    >
      {TABS.map((tab) => {
        const disabled = tab.id === "roulette" && rouletteDisabled;
        const isActive = active === tab.id;
        const showLabel = hoveredId === tab.id && !disabled;
        return (
          <div
            key={tab.id}
            style={{ position: "relative" }}
            onMouseEnter={() => setHoveredId(tab.id)}
            onMouseLeave={() => setHoveredId(null)}
          >
            <button
              type="button"
              onClick={() => onChange(tab.id)}
              disabled={disabled}
              aria-label={tab.label}
              aria-disabled={disabled || undefined}
              style={{
                width: 36,
                height: 36,
                borderRadius: "50%",
                border: "none",
                cursor: disabled ? "not-allowed" : "pointer",
                background: isActive ? colors.cta : "transparent",
                color: isActive ? "#FFFFFF" : colors.inkSoft,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                opacity: disabled ? 0.4 : 1,
                transition: "background 160ms ease-out, color 160ms ease-out",
                padding: 0,
              }}
              onMouseEnter={(e) => {
                if (disabled || isActive) return;
                e.currentTarget.style.background = `${colors.cta}12`;
                e.currentTarget.style.color = colors.cta;
              }}
              onMouseLeave={(e) => {
                if (disabled || isActive) return;
                e.currentTarget.style.background = "transparent";
                e.currentTarget.style.color = colors.inkSoft;
              }}
            >
              <tab.Icon size={20} color="currentColor" />
            </button>

            {showLabel && (
              <div
                role="tooltip"
                style={{
                  position: "absolute",
                  left: 48,
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
                  animation:
                    "maygo-bubble-pop 180ms cubic-bezier(0.2,0.8,0.2,1) both",
                  boxShadow: `0 6px 16px ${colors.shadow}`,
                }}
              >
                {tab.label}
              </div>
            )}
          </div>
        );
      })}

      {/* 하단 알림 벨 (선택) — 프로필 바로 위에 배치. */}
      {notificationBell && (
        <div
          style={{
            marginTop: "auto",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          {notificationBell}
        </div>
      )}

      {/* 하단 프로필 — 담백한 사람 아이콘. */}
      <div
        style={{
          marginTop: notificationBell ? 6 : "auto",
          position: "relative",
        }}
        onMouseEnter={() => setHoveredId("profile")}
        onMouseLeave={() => setHoveredId(null)}
      >
        <Link
          href="/settings"
          aria-label={myNickname ? `${myNickname}님 마이페이지` : "마이페이지"}
          title={myNickname ? `${myNickname}님` : "마이페이지"}
          style={{
            width: 36,
            height: 36,
            borderRadius: "50%",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            background: colors.bg,
            border: `1px solid ${colors.hairline}`,
            color: colors.inkSoft,
            textDecoration: "none",
          }}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="9" r="3.6" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <path
              d="M5.5 19.5c0-3.5 2.9-6 6.5-6s6.5 2.5 6.5 6"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
            />
          </svg>
        </Link>
        {hoveredId === "profile" && (
          <div
            role="tooltip"
            style={{
              position: "absolute",
              left: 48,
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
              animation:
                "maygo-bubble-pop 180ms cubic-bezier(0.2,0.8,0.2,1) both",
              boxShadow: `0 6px 16px ${colors.shadow}`,
            }}
          >
            {myNickname ? `${myNickname}님` : "마이페이지"}
          </div>
        )}
      </div>
    </div>
  );
}
