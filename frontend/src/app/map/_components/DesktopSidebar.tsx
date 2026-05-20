"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { colors, fonts } from "@/lib/design/tokens";
import {
  IconSearch,
  IconPlus,
  IconShuffle,
} from "@/components/icons";
import type { ActionBarTab } from "./types";

interface DesktopSidebarProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
  /** 셔플 탭만 비활성화 (위치 권한 거부 등). */
  rouletteDisabled?: boolean;
  /** 로그인한 사용자 닉네임 (하단 마이페이지 진입 영역에 노출). */
  myNickname?: string;
  /** 펼침 상태 외부 알림 — SidePanel left 오프셋 보정용. */
  onExpandedChange?: (expanded: boolean) => void;
}

const COLLAPSED_WIDTH = 52;
const EXPANDED_WIDTH = 220;

/**
 * 데스크탑 좌측 세로 사이드바.
 *
 * 두 가지 모드:
 *  - 기본(접힘, 52px): 햄버거(≡) + 서비스 로고 + 아이콘만
 *  - 펼침(220px): ✕ + 로고 워드마크 + 아이콘 옆 텍스트 라벨
 *
 * 외부 클릭 또는 탭 선택 시 자동으로 접힘으로 돌아간다.
 * 펼침 너비 변화를 onExpandedChange 로 부모에 전달해 SidePanel 좌측 오프셋과 동기화.
 */
export default function DesktopSidebar({
  active,
  onChange,
  rouletteDisabled = false,
  myNickname,
  onExpandedChange,
}: DesktopSidebarProps) {
  const [expanded, setExpanded] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    onExpandedChange?.(expanded);
  }, [expanded, onExpandedChange]);

  // 외부 클릭 시 자동 접힘.
  useEffect(() => {
    if (!expanded) return;
    const handleDocClick = (e: MouseEvent) => {
      if (!rootRef.current) return;
      if (rootRef.current.contains(e.target as Node)) return;
      setExpanded(false);
    };
    document.addEventListener("mousedown", handleDocClick);
    return () => document.removeEventListener("mousedown", handleDocClick);
  }, [expanded]);

  const handleTabClick = useCallback(
    (tab: Exclude<ActionBarTab, null>) => {
      setExpanded(false);
      onChange(tab);
    },
    [onChange],
  );

  const tabs: Array<{
    id: Exclude<ActionBarTab, null>;
    Icon: typeof IconSearch;
    label: string;
  }> = [
    { id: "search", Icon: IconSearch, label: "장소 검색" },
    { id: "add", Icon: IconPlus, label: "장소 추가" },
    { id: "roulette", Icon: IconShuffle, label: "오늘 어디 갈까?" },
  ];

  return (
    <div
      ref={rootRef}
      style={{
        position: "absolute",
        top: 14,
        bottom: 14,
        left: 14,
        width: expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH,
        background: colors.panel,
        border: `1px solid ${colors.hairline}`,
        borderRadius: 20,
        display: "flex",
        flexDirection: "column",
        paddingTop: 10,
        gap: 4,
        zIndex: 24,
        transition: "width 200ms cubic-bezier(0.2, 0.8, 0.2, 1)",
        overflow: "hidden",
        boxShadow: `0 10px 28px ${colors.shadowMd}`,
      }}
    >
      {/* 헤더: 접힘 시 [≡] / [로고] 두 줄, 펼침 시 [✕  로고 우리가 갈 지도] 한 줄.
          정렬: ✕ 아이콘 left=20 (탭 아이콘과 동일 column), 로고 left=54 (탭 라벨과 동일 column). */}
      {expanded ? (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 12,
            paddingLeft: 20,
            paddingRight: 12,
            paddingTop: 6,
            paddingBottom: 12,
            borderBottom: `1px solid ${colors.hairline}`,
            flexShrink: 0,
          }}
        >
          <button
            type="button"
            onClick={() => setExpanded(false)}
            aria-label="메뉴 접기"
            style={{
              width: 22,
              height: 22,
              border: "none",
              background: "transparent",
              cursor: "pointer",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: colors.inkSoft,
              padding: 0,
              flexShrink: 0,
            }}
          >
            <svg width={22} height={22} viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <line x1="6" y1="6" x2="18" y2="18" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <line x1="18" y1="6" x2="6" y2="18" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          </button>
          <svg
            width={22}
            height={22}
            viewBox="0 0 24 24"
            aria-hidden="true"
            style={{ display: "block", flexShrink: 0 }}
          >
            <circle cx="12" cy="13.5" r="7.5" fill="none" stroke={colors.ink} strokeWidth="1.2" />
            <ellipse cx="12" cy="13.5" rx="7.5" ry="3" fill="none" stroke={colors.ink} strokeWidth="1" opacity="0.55" />
            <path
              d="M12 1.5c-3 0-5.4 2.4-5.4 5.4 0 3.6 5.4 9 5.4 9s5.4-5.4 5.4-9c0-3-2.4-5.4-5.4-5.4z"
              fill={colors.pinMemory}
              stroke={colors.ink}
              strokeWidth="1.2"
              strokeLinejoin="round"
            />
            <path
              d="M12 9.3l-0.55-0.5C10.1 7.6 9 6.7 9 5.55c0-0.94 0.74-1.68 1.68-1.68 0.53 0 1.04 0.25 1.32 0.64 0.28-0.39 0.79-0.64 1.32-0.64C14.26 3.87 15 4.61 15 5.55c0 1.15-1.1 2.05-2.45 3.25L12 9.3z"
              fill="#FFFFFF"
            />
          </svg>
          <span
            style={{
              fontFamily: fonts.emo,
              fontSize: 16,
              fontWeight: 700,
              color: colors.ink,
              letterSpacing: -0.5,
              lineHeight: 1,
              whiteSpace: "nowrap",
            }}
          >
            우리가 갈 지도
          </span>
        </div>
      ) : (
        <>
          <button
            type="button"
            onClick={() => setExpanded(true)}
            aria-label="메뉴 펼치기"
            style={{
              width: COLLAPSED_WIDTH,
              height: 44,
              border: "none",
              background: "transparent",
              cursor: "pointer",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: colors.inkSoft,
              padding: 0,
              flexShrink: 0,
            }}
          >
            <svg width={22} height={22} viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <line x1="4" y1="7" x2="20" y2="7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <line x1="4" y1="12" x2="20" y2="12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <line x1="4" y1="17" x2="20" y2="17" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          </button>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              paddingTop: 6,
              paddingBottom: 12,
              marginBottom: 4,
              borderBottom: `1px solid ${colors.hairline}`,
              flexShrink: 0,
            }}
          >
            <svg
              width={22}
              height={22}
              viewBox="0 0 24 24"
              aria-hidden="true"
              style={{ display: "block", flexShrink: 0 }}
            >
              <circle cx="12" cy="13.5" r="7.5" fill="none" stroke={colors.ink} strokeWidth="1.2" />
              <ellipse cx="12" cy="13.5" rx="7.5" ry="3" fill="none" stroke={colors.ink} strokeWidth="1" opacity="0.55" />
              <path
                d="M12 1.5c-3 0-5.4 2.4-5.4 5.4 0 3.6 5.4 9 5.4 9s5.4-5.4 5.4-9c0-3-2.4-5.4-5.4-5.4z"
                fill={colors.pinMemory}
                stroke={colors.ink}
                strokeWidth="1.2"
                strokeLinejoin="round"
              />
              <path
                d="M12 9.3l-0.55-0.5C10.1 7.6 9 6.7 9 5.55c0-0.94 0.74-1.68 1.68-1.68 0.53 0 1.04 0.25 1.32 0.64 0.28-0.39 0.79-0.64 1.32-0.64C14.26 3.87 15 4.61 15 5.55c0 1.15-1.1 2.05-2.45 3.25L12 9.3z"
                fill="#FFFFFF"
              />
            </svg>
          </div>
        </>
      )}

      {/* 탭 버튼들 */}
      <div style={{ display: "flex", flexDirection: "column", gap: 4, padding: "4px 8px" }}>
        {tabs.map((tab) => {
          const disabled = tab.id === "roulette" && rouletteDisabled;
          const isActive = active === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => handleTabClick(tab.id)}
              disabled={disabled}
              aria-label={tab.label}
              aria-disabled={disabled || undefined}
              style={{
                width: "100%",
                height: 44,
                borderRadius: 12,
                cursor: disabled ? "not-allowed" : "pointer",
                border: "none",
                background: isActive ? `${colors.cta}1a` : "transparent",
                display: "flex",
                alignItems: "center",
                justifyContent: expanded ? "flex-start" : "center",
                paddingLeft: expanded ? 12 : 0,
                gap: 12,
                position: "relative",
                opacity: disabled ? 0.4 : 1,
                transition: "background 160ms ease-out",
              }}
              onMouseEnter={(e) => {
                if (disabled || isActive) return;
                e.currentTarget.style.background = `${colors.cta}0a`;
              }}
              onMouseLeave={(e) => {
                if (disabled || isActive) return;
                e.currentTarget.style.background = "transparent";
              }}
            >
              {isActive && !expanded && (
                <div
                  aria-hidden="true"
                  style={{
                    position: "absolute",
                    left: -8,
                    width: 3,
                    height: 22,
                    background: colors.cta,
                    borderRadius: 2,
                  }}
                />
              )}
              <tab.Icon
                size={22}
                color={isActive ? colors.cta : colors.inkSoft}
              />
              {expanded && (
                <span
                  style={{
                    fontFamily: fonts.sans,
                    fontSize: 14,
                    fontWeight: isActive ? 700 : 500,
                    color: isActive ? colors.cta : colors.ink,
                    whiteSpace: "nowrap",
                  }}
                >
                  {tab.label}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* 하단 고정 — 마이페이지 진입 */}
      <Link
        href="/settings"
        aria-label={myNickname ? `${myNickname}님 마이페이지` : "마이페이지"}
        title={myNickname ? `${myNickname}님` : "마이페이지"}
        onClick={() => setExpanded(false)}
        style={{
          marginTop: "auto",
          margin: "auto 8px 12px 8px",
          height: 44,
          borderRadius: 10,
          display: "flex",
          alignItems: "center",
          justifyContent: expanded ? "flex-start" : "center",
          paddingLeft: expanded ? 8 : 0,
          gap: 12,
          color: colors.ink,
          textDecoration: "none",
          background: "transparent",
        }}
      >
        <div
          aria-hidden="true"
          style={{
            width: 32,
            height: 32,
            borderRadius: "50%",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            background: colors.bg,
            border: `1px solid ${colors.hairline}`,
            color: colors.inkSoft,
            flexShrink: 0,
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
        </div>
        {expanded && (
          <span
            style={{
              fontFamily: fonts.sans,
              fontSize: 14,
              fontWeight: 500,
              color: colors.ink,
              whiteSpace: "nowrap",
            }}
          >
            내 프로필
          </span>
        )}
      </Link>
    </div>
  );
}
