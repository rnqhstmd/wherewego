"use client";

import { Fragment } from "react";
import { colors } from "@/lib/design/tokens";
import { IconSearch, IconPlus, IconShuffle } from "@/components/icons";
import type { ActionBarTab } from "./types";

interface ActionBarProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
}

/**
 * 모바일 하단 액션바 — 검색 / 추가 / 룰렛 3개 탭.
 * 활성 탭은 cta 컬러로 표시.
 */
export default function ActionBar({ active, onChange }: ActionBarProps) {
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
          <button
            type="button"
            onClick={() => onChange(tab.id)}
            style={{
              flex: 1,
              height: "100%",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              cursor: "pointer",
              background: "transparent",
              border: "none",
            }}
            aria-label={tab.id}
          >
            <tab.Icon
              size={22}
              color={active === tab.id ? colors.cta : colors.inkSoft}
            />
          </button>
        </Fragment>
      ))}
    </div>
  );
}
