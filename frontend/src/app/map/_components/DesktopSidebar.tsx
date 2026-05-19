"use client";

import Link from "next/link";
import { colors } from "@/lib/design/tokens";
import {
  IconSearch,
  IconPlus,
  IconShuffle,
  IconBack,
} from "@/components/icons";
import type { ActionBarTab } from "./types";

interface DesktopSidebarProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
  /** 셔플 탭만 비활성화 (위치 권한 거부 등). */
  rouletteDisabled?: boolean;
  /** 로그인한 사용자 닉네임 (하단 마이페이지 진입 영역에 노출). */
  myNickname?: string;
}

/**
 * 데스크탑 좌측 52px 세로 사이드바 — ActionBar 와 동일 인터페이스.
 * design-bundle/project/screens-desktop.jsx 사양 기반.
 */
export default function DesktopSidebar({
  active,
  onChange,
  rouletteDisabled = false,
  myNickname,
}: DesktopSidebarProps) {
  const tabs = [
    { id: "search" as const, Icon: IconSearch },
    { id: "add" as const, Icon: IconPlus },
    { id: "roulette" as const, Icon: IconShuffle },
  ];

  return (
    <div
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        bottom: 0,
        width: 52,
        background: colors.panel,
        borderRight: `1px solid ${colors.hairline}`,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        paddingTop: 16,
        gap: 8,
        zIndex: 15,
      }}
    >
      {/* 그룹 목록으로 뒤로가기 — 항상 상단 고정 */}
      <Link
        href="/groups"
        aria-label="그룹 목록"
        style={{
          width: 40,
          height: 40,
          borderRadius: 10,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: colors.inkSoft,
          textDecoration: "none",
          marginBottom: 8,
        }}
      >
        <IconBack size={20} color={colors.inkSoft} />
      </Link>
      {tabs.map((tab) => {
        const disabled = tab.id === "roulette" && rouletteDisabled;
        return (
          <button
            key={tab.id}
            type="button"
            onClick={() => onChange(tab.id)}
            disabled={disabled}
            aria-label={tab.id}
            aria-disabled={disabled || undefined}
            style={{
              width: 40,
              height: 40,
              borderRadius: 10,
              cursor: disabled ? "not-allowed" : "pointer",
              border: "none",
              background:
                active === tab.id ? `${colors.cta}15` : "transparent",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              position: "relative",
              opacity: disabled ? 0.4 : 1,
            }}
          >
            {active === tab.id && (
              <div
                style={{
                  position: "absolute",
                  left: -16,
                  width: 3,
                  height: 24,
                  background: colors.cta,
                  borderRadius: 2,
                }}
              />
            )}
            <tab.Icon
              size={22}
              color={active === tab.id ? colors.cta : colors.inkSoft}
            />
          </button>
        );
      })}
      {/* 하단 고정 — 마이페이지 진입. 사용자 닉네임 첫 글자 아바타 + 호버 시 풀 닉네임. */}
      <Link
        href="/settings"
        aria-label={myNickname ? `${myNickname}님 마이페이지` : "마이페이지"}
        title={myNickname ? `${myNickname}님` : "마이페이지"}
        style={{
          marginTop: "auto",
          marginBottom: 12,
          width: 40,
          height: 40,
          borderRadius: "50%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: `linear-gradient(135deg, ${colors.pinMemory}, ${colors.pinPlace})`,
          color: "#ffffff",
          textDecoration: "none",
          fontFamily: "var(--font-emo), 'Gowun Batang', serif",
          fontSize: 16,
          fontWeight: 700,
        }}
      >
        {myNickname ? myNickname.slice(0, 1) : "?"}
      </Link>
    </div>
  );
}
