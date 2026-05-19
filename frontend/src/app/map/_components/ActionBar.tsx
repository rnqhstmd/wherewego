"use client";

import { Fragment } from "react";
import Link from "next/link";
import { colors, fonts } from "@/lib/design/tokens";
import { IconSearch, IconPlus, IconShuffle } from "@/components/icons";
import type { ActionBarTab } from "./types";

interface ActionBarProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
  /** 셔플 탭만 비활성화 (위치 권한 거부 등). */
  rouletteDisabled?: boolean;
  /** 마이페이지 아바타에 노출할 닉네임 첫 글자. */
  myNickname?: string;
}

/**
 * 모바일 하단 액션바 — 검색 / 추가 / 룰렛 + 마이페이지 진입 4분할.
 * 활성 탭은 cta 컬러로 표시. 마이페이지는 navigation이므로 active state 없음.
 */
export default function ActionBar({
  active,
  onChange,
  rouletteDisabled = false,
  myNickname,
}: ActionBarProps) {
  const tabs = [
    { id: "search" as const, Icon: IconSearch },
    { id: "add" as const, Icon: IconPlus },
    { id: "roulette" as const, Icon: IconShuffle },
  ];
  const initial = myNickname?.trim().charAt(0) ?? "";

  return (
    <div
      style={{
        position: "absolute",
        bottom: 0,
        left: 0,
        right: 0,
        height: 64,
        background: colors.panel,
        borderTop: `1px solid ${colors.hairline}`,
        boxShadow: `0 -2px 12px ${colors.shadow}`,
        display: "flex",
        alignItems: "center",
        zIndex: 25,
      }}
    >
      {tabs.map((tab, i) => (
        <Fragment key={tab.id}>
          {i > 0 && (
            <div
              style={{
                width: 1,
                height: 24,
                background: colors.hairline,
              }}
            />
          )}
          {(() => {
            const disabled = tab.id === "roulette" && rouletteDisabled;
            return (
              <button
                type="button"
                onClick={() => onChange(tab.id)}
                disabled={disabled}
                style={{
                  flex: 1,
                  height: "100%",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  cursor: disabled ? "not-allowed" : "pointer",
                  background: "transparent",
                  border: "none",
                  opacity: disabled ? 0.4 : 1,
                }}
                aria-label={tab.id}
                aria-disabled={disabled || undefined}
              >
                <tab.Icon
                  size={22}
                  color={active === tab.id ? colors.cta : colors.inkSoft}
                />
              </button>
            );
          })()}
        </Fragment>
      ))}
      <div
        style={{
          width: 1,
          height: 24,
          background: colors.hairline,
        }}
      />
      <Link
        href="/settings"
        aria-label="마이페이지"
        style={{
          flex: 1,
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          textDecoration: "none",
        }}
      >
        <div
          aria-hidden="true"
          style={{
            width: 30,
            height: 30,
            borderRadius: "50%",
            background: `linear-gradient(135deg, ${colors.pinMemory}, ${colors.pinPlace})`,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            color: "#ffffff",
            fontFamily: fonts.sans,
            fontSize: 13,
            fontWeight: 700,
            letterSpacing: -0.3,
          }}
        >
          {initial}
        </div>
      </Link>
    </div>
  );
}
