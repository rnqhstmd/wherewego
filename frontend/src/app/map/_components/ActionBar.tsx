"use client";

import { colors, fonts } from "@/lib/design/tokens";
import { IconSearch, IconPlus, IconShuffle } from "@/components/icons";
import type { ActionBarTab } from "./types";

interface ActionBarProps {
  active: ActionBarTab;
  onChange: (tab: Exclude<ActionBarTab, null>) => void;
  /** 셔플 탭만 비활성화 (위치 권한 거부 등). */
  rouletteDisabled?: boolean;
}

const TABS: Array<{
  id: Exclude<ActionBarTab, null>;
  Icon: typeof IconSearch;
  label: string;
}> = [
  { id: "search", Icon: IconSearch, label: "검색" },
  { id: "add", Icon: IconPlus, label: "추가" },
  { id: "roulette", Icon: IconShuffle, label: "어디 갈까?" },
];

/**
 * 모바일 하단 액션바 — 둥근 카드형 + 아이콘 위 라벨 (option C 디자인).
 *
 * - 하단/좌우 12px 여백 → floating 느낌
 * - 둥근 모서리 18px + 부드러운 그림자
 * - 각 탭: 아이콘 + 라벨 세로 스택
 * - 활성 탭: cta 컬러 아이콘/텍스트 + 위에 작은 cta 도트로 강조
 *
 * 프로필 진입(/settings)은 상단 MobileTopNav 로 이동.
 */
export default function ActionBar({
  active,
  onChange,
  rouletteDisabled = false,
}: ActionBarProps) {
  return (
    <div
      style={{
        position: "absolute",
        bottom: 12,
        left: 12,
        right: 12,
        height: 68,
        background: colors.panel,
        borderRadius: 18,
        border: `1px solid ${colors.hairline}`,
        boxShadow: `0 8px 24px ${colors.shadow}`,
        display: "flex",
        alignItems: "stretch",
        zIndex: 25,
        overflow: "hidden",
      }}
    >
      {TABS.map((tab) => {
        const disabled = tab.id === "roulette" && rouletteDisabled;
        const isActive = active === tab.id;
        return (
          <button
            key={tab.id}
            type="button"
            onClick={() => onChange(tab.id)}
            disabled={disabled}
            aria-label={tab.label}
            aria-disabled={disabled || undefined}
            style={{
              flex: 1,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 4,
              cursor: disabled ? "not-allowed" : "pointer",
              background: "transparent",
              border: "none",
              opacity: disabled ? 0.4 : 1,
              padding: 0,
              position: "relative",
            }}
          >
            <tab.Icon
              size={22}
              color={isActive ? colors.cta : colors.inkSoft}
            />
            <span
              style={{
                fontFamily: fonts.sans,
                fontSize: 11,
                fontWeight: isActive ? 700 : 500,
                color: isActive ? colors.cta : colors.inkSoft,
                letterSpacing: -0.2,
                lineHeight: 1,
              }}
            >
              {tab.label}
            </span>
          </button>
        );
      })}
    </div>
  );
}
