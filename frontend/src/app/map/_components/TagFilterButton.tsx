"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { PinTag } from "@/lib/api/types";
import { colors, fonts } from "@/lib/design/tokens";
import { MemoryGlyph, PIN_COLORS, ReelGlyph, WishGlyph } from "@/lib/pin/markers";

const TAG_OPTIONS: Array<{
  tag: PinTag;
  label: string;
  color: string;
  Glyph: () => React.ReactNode;
}> = [
  {
    tag: "MEMORY",
    label: "추억",
    color: PIN_COLORS.memory,
    Glyph: () => <MemoryGlyph w={14} h={14} color={PIN_COLORS.memory} />,
  },
  {
    tag: "WISH",
    label: "위시",
    color: PIN_COLORS.wish,
    Glyph: () => <WishGlyph size={14} color={PIN_COLORS.wish} />,
  },
  {
    tag: "REEL",
    label: "발견",
    color: PIN_COLORS.reel,
    Glyph: () => <ReelGlyph size={14} color={PIN_COLORS.reel} />,
  },
];

interface TagFilterButtonProps {
  visibleTags: Set<PinTag>;
  onChange: (next: Set<PinTag>) => void;
  /**
   * Phase 12 (FR-PIN-12-26, 설계 §9.6 + D-13): 발견 탭의 "관심 있는 발견" 필터.
   *
   * - false (기본): 모든 REEL 핀 표시.
   * - true: REEL 핀 중 `wantCount >= 1` 인 핀만 표시 — "🙋 관심 있는 발견".
   *
   * UI 는 발견 탭 라벨에 inline 토글 칩으로 노출되며, 토글 ON 시 라벨이 "발견 (관심)" 으로 변경된다.
   * 상위 컨테이너는 본 값을 URL 쿼리(`?interest=true`) 및 핀 fetch 옵션과 연결한다.
   */
  interestOnly?: boolean;
  onInterestChange?: (next: boolean) => void;
}

export function TagFilterButton({
  visibleTags,
  onChange,
  interestOnly = false,
  onInterestChange,
}: TagFilterButtonProps) {
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

  const allChecked = visibleTags.size === TAG_OPTIONS.length;
  // Phase 12: 발견 interest 토글도 활성 필터로 간주하여 dot indicator 를 띄운다.
  const isFiltering = !allChecked || interestOnly;

  const toggleAll = () => {
    if (allChecked) {
      onChange(new Set());
    } else {
      onChange(new Set(TAG_OPTIONS.map((o) => o.tag)));
    }
  };

  const toggleTag = (tag: PinTag) => {
    const next = new Set(visibleTags);
    if (next.has(tag)) {
      next.delete(tag);
    } else {
      next.add(tag);
    }
    onChange(next);
  };

  const activeAccent = useMemo(() => colors.cta, []);

  return (
    <div ref={wrapRef} style={{ position: "relative" }}>
      <button
        type="button"
        aria-label="태그 필터"
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
        {/* 필터(깔때기) 아이콘 */}
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
          aria-label="태그 필터"
          style={{
            position: "absolute",
            bottom: 52,
            left: 0,
            background: colors.panel,
            borderRadius: 16,
            boxShadow: `0 8px 28px rgba(0,0,0,0.14)`,
            border: `1px solid ${colors.hairline}`,
            padding: "12px 14px 8px",
            minWidth: 184,
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
            보고 싶은 태그만 골라요
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

          {TAG_OPTIONS.map(({ tag, label, color, Glyph }) => {
            // Phase 12 (FR-PIN-12-26): 발견 항목은 라벨에 (관심) 접미사 + 토글 칩 부착.
            const isReelRow = tag === "REEL";
            const displayLabel =
              isReelRow && interestOnly ? `${label} (관심)` : label;
            const trailing =
              isReelRow && onInterestChange ? (
                <button
                  type="button"
                  role="switch"
                  aria-checked={interestOnly}
                  aria-label={
                    interestOnly
                      ? "관심 있는 발견만 보기 끄기"
                      : "관심 있는 발견만 보기"
                  }
                  onClick={(e) => {
                    e.stopPropagation();
                    onInterestChange(!interestOnly);
                  }}
                  title="🙋 관심 있는 발견 (가고 싶어요 1+)"
                  style={{
                    padding: "3px 8px",
                    borderRadius: 999,
                    border: `1px solid ${
                      interestOnly ? colors.cta : colors.hairline
                    }`,
                    background: interestOnly ? `${colors.cta}1A` : "transparent",
                    color: interestOnly ? colors.cta : colors.inkSoft,
                    fontFamily: fonts.sans,
                    fontSize: 10,
                    fontWeight: 700,
                    cursor: "pointer",
                    lineHeight: 1,
                    flexShrink: 0,
                  }}
                >
                  🙋 관심
                </button>
              ) : undefined;
            return (
              <CheckboxRow
                key={tag}
                label={displayLabel}
                checked={visibleTags.has(tag)}
                onToggle={() => toggleTag(tag)}
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
                trailing={trailing}
              />
            );
          })}
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
  /**
   * Phase 12: 행 우측에 부가 액션(예: 발견 탭의 "🙋 관심" 토글)을 배치할 수 있는 슬롯.
   * 부모 체크박스 토글과 충돌하지 않도록 trailing 의 onClick 은 stopPropagation 책임을 가진다.
   */
  trailing?: React.ReactNode;
}

function CheckboxRow({
  label,
  checked,
  onToggle,
  emphasize,
  accent,
  leading,
  trailing,
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
        padding: "6px 4px",
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
          fontSize: 13,
          color: colors.ink,
          fontWeight: emphasize ? 700 : 500,
          lineHeight: 1.3,
          flex: 1,
        }}
      >
        {label}
      </span>
      {trailing}
    </button>
  );
}
