"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import {
  InterestBadgeIcon,
  MemoryGlyph,
  PIN_COLORS,
  ReelGlyph,
  WishGlyph,
} from "@/lib/pin/markers";

/**
 * Phase 12 후속(UX 재반영): 체크박스 dropdown 필터 (이전 패턴 복귀).
 *
 * 항목은 4가지 + 전체:
 *  - 추억(MEMORY) / 위시(WISH) / 발견(REEL, wantCount=0) / 관심(REEL && wantCount>=1)
 *
 * 관심은 발견과 **상호배타적인 서브셋** — 관심만 켜면 want_count>=1 인 발견 핀만 노출되고,
 * 발견만 켜면 want_count=0 인 순수 발견 핀만 노출된다. 둘 다 켜면 모든 발견 핀이 노출된다.
 * 체크박스 의미가 각자 독립적이 되어 사용자 직관에 부합한다.
 */
export type FilterKey = "MEMORY" | "WISH" | "REEL" | "INTEREST";

export const ALL_FILTER_KEYS: ReadonlyArray<FilterKey> = [
  "MEMORY",
  "WISH",
  "REEL",
  "INTEREST",
];

interface OptionMeta {
  key: FilterKey;
  label: string;
  color: string;
  Glyph: () => React.ReactNode;
}

// 설명 텍스트는 좌하단 ! (TagLegendButton) 에 위임. 본 필터는 라벨/아이콘만.
const OPTIONS: ReadonlyArray<OptionMeta> = [
  {
    key: "MEMORY",
    label: "추억",
    color: PIN_COLORS.memory,
    Glyph: () => <MemoryGlyph w={14} h={14} color={PIN_COLORS.memory} />,
  },
  {
    key: "WISH",
    label: "위시",
    color: PIN_COLORS.wish,
    Glyph: () => <WishGlyph size={14} color={PIN_COLORS.wish} />,
  },
  {
    key: "REEL",
    label: "발견",
    color: PIN_COLORS.reel,
    Glyph: () => <ReelGlyph size={14} color={PIN_COLORS.reel} />,
  },
  {
    key: "INTEREST",
    label: "관심",
    color: PIN_COLORS.reel,
    Glyph: () => <InterestBadgeIcon size={14} />,
  },
];

interface TagFilterButtonProps {
  selected: Set<FilterKey>;
  onChange: (next: Set<FilterKey>) => void;
}

export function TagFilterButton({ selected, onChange }: TagFilterButtonProps) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent | TouchEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("touchstart", onDown);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("touchstart", onDown);
    };
  }, [open]);

  const allChecked = selected.size === ALL_FILTER_KEYS.length;
  const isFiltering = !allChecked;

  const toggleAll = () => {
    if (allChecked) onChange(new Set());
    else onChange(new Set(ALL_FILTER_KEYS));
  };

  const toggleKey = (key: FilterKey) => {
    const next = new Set(selected);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    onChange(next);
  };

  const activeAccent = useMemo(() => colors.cta, []);

  return (
    <div ref={wrapRef} style={{ position: "relative" }}>
      <button
        type="button"
        aria-label="핀 필터"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        style={{
          width: 44,
          height: 44,
          borderRadius: "50%",
          background: colors.panel,
          border: `1px solid ${colors.hairline}`,
          boxShadow: `0 6px 18px ${colors.shadow}`,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          cursor: "pointer",
          padding: 0,
          flexShrink: 0,
          position: "relative",
        }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M4 5h16l-6 8v5l-4 2v-7L4 5z"
            fill="none"
            stroke={colors.inkSoft}
            strokeWidth="1.6"
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        </svg>
        {isFiltering && (
          <span
            aria-hidden="true"
            style={{
              position: "absolute",
              top: 7,
              right: 7,
              width: 8,
              height: 8,
              borderRadius: "50%",
              background: activeAccent,
              border: `1.5px solid ${colors.panel}`,
            }}
          />
        )}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="핀 필터"
          style={{
            position: "absolute",
            bottom: 52,
            left: 0,
            background: colors.panel,
            borderRadius: 16,
            boxShadow: `0 8px 28px rgba(0,0,0,0.14)`,
            border: `1px solid ${colors.hairline}`,
            padding: "12px 14px 8px",
            minWidth: 220,
            zIndex: 60,
            fontFamily: fonts.sans,
            animation: "maygo-bubble-pop 200ms cubic-bezier(0.2,0.8,0.2,1) both",
          }}
        >
          <svg
            width="14"
            height="8"
            viewBox="0 0 14 8"
            aria-hidden="true"
            style={{ position: "absolute", bottom: -8, left: 16 }}
          >
            <path d="M0 0 L14 0 L7 8 Z" fill={colors.hairline} />
            <path d="M1 0 L13 0 L7 7 Z" fill={colors.panel} />
            <rect x="1" y="0" width="12" height="1.5" fill={colors.panel} />
          </svg>

          <p
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: colors.inkFaint,
              marginBottom: 8,
              letterSpacing: 0.3,
            }}
          >
            보고 싶은 핀을 골라요
          </p>

          <CheckboxRow
            label="전체"
            checked={allChecked}
            onToggle={toggleAll}
            emphasize
          />

          <div
            style={{
              height: 1,
              background: colors.hairline,
              margin: "6px -14px",
            }}
          />

          {OPTIONS.map(({ key, label, color, Glyph }) => (
            <CheckboxRow
              key={key}
              label={label}
              checked={selected.has(key)}
              onToggle={() => toggleKey(key)}
              accent={color}
              leading={
                <span
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: "50%",
                    background: `${color}1A`,
                    display: "inline-flex",
                    alignItems: "center",
                    justifyContent: "center",
                    flexShrink: 0,
                  }}
                >
                  <Glyph />
                </span>
              }
            />
          ))}
        </div>
      )}
    </div>
  );
}

interface CheckboxRowProps {
  label: string;
  checked: boolean;
  onToggle: () => void;
  emphasize?: boolean;
  accent?: string;
  leading?: React.ReactNode;
}

function CheckboxRow({
  label,
  checked,
  onToggle,
  emphasize,
  accent,
  leading,
}: CheckboxRowProps) {
  const fill = accent ?? colors.cta;
  return (
    <button
      type="button"
      onClick={onToggle}
      role="checkbox"
      aria-checked={checked}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        width: "100%",
        padding: "5px 4px",
        background: "transparent",
        border: "none",
        cursor: "pointer",
        borderRadius: 8,
        textAlign: "left",
      }}
    >
      <span
        style={{
          width: 18,
          height: 18,
          borderRadius: 5,
          border: checked
            ? `1.5px solid ${fill}`
            : `1.5px solid ${colors.hairline}`,
          background: checked ? fill : "transparent",
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
          transition: "background 120ms ease, border-color 120ms ease",
        }}
      >
        {checked && (
          <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden="true">
            <path
              d="M2 6.5 L5 9.5 L10 3.5"
              fill="none"
              stroke="#ffffff"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </span>
      {leading}
      <span
        style={{
          flex: 1,
          minWidth: 0,
          fontSize: 13,
          color: colors.ink,
          fontWeight: emphasize ? 700 : 600,
          lineHeight: 1.3,
        }}
      >
        {label}
      </span>
    </button>
  );
}
