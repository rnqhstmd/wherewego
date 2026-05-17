"use client";

import { colors } from "@/lib/design/tokens";
import { IconSearch, IconPlus, IconShuffle } from "@/components/icons";
import type { ActionBarTab } from "./types";

interface DesktopSidebarProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
}

/**
 * 데스크탑 좌측 52px 세로 사이드바 — ActionBar 와 동일 인터페이스.
 * design-bundle/project/screens-desktop.jsx 사양 기반.
 */
export default function DesktopSidebar({
  active,
  onChange,
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
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          onClick={() => onChange(tab.id)}
          aria-label={tab.id}
          style={{
            width: 40,
            height: 40,
            borderRadius: 10,
            cursor: "pointer",
            border: "none",
            background:
              active === tab.id ? `${colors.cta}15` : "transparent",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            position: "relative",
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
      ))}
    </div>
  );
}
