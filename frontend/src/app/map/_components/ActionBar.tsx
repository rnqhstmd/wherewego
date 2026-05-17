"use client";

import { Fragment } from "react";
import { colors } from "@/lib/design/tokens";
import { IconSearch, IconPlus, IconShuffle } from "@/components/icons";
import type { ActionBarTab } from "./types";

interface ActionBarProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
  /** 셔플 탭만 비활성화 (위치 권한 거부 등). */
  rouletteDisabled?: boolean;
}

/**
 * 모바일 하단 액션바 — 검색 / 추가 / 룰렛 3개 탭.
 * 활성 탭은 cta 컬러로 표시.
 */
export default function ActionBar({
  active,
  onChange,
  rouletteDisabled = false,
}: ActionBarProps) {
  const tabs = [
    { id: "search" as const, Icon: IconSearch },
    { id: "add" as const, Icon: IconPlus },
    { id: "roulette" as const, Icon: IconShuffle },
  ];

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
        zIndex: 15,
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
    </div>
  );
}
