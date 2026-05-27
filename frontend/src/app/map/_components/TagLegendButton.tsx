"use client";

import { useEffect, useRef, useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import {
  InterestBadgeIcon,
  MemoryGlyph,
  PIN_COLORS,
  ReelGlyph,
  WishGlyph,
} from "@/lib/pin/markers";

/**
 * 좌측 하단 ! 아이콘 버튼.
 * 클릭 시 (1) 4단계 핀 진행 (2) 아이콘 안내(하트 등)를 통합 표시한다.
 *
 * Phase 12 후속(UX 개선): PinPopup 의 ? 버튼을 폐기하고 본 버튼으로 일원화.
 * TagProgressModal 의 다이어그램 + 하트 의미 설명을 본 popover 안에 인라인 노출.
 */

interface StageMeta {
  label: string;
  desc: string;
  color: string;
  Glyph: () => React.ReactNode;
}

const STAGES: ReadonlyArray<StageMeta> = [
  {
    label: "발견",
    desc: "릴스나 직접 추가로 새롭게 등장한 곳",
    color: PIN_COLORS.reel,
    Glyph: () => <ReelGlyph size={14} color={PIN_COLORS.reel} />,
  },
  {
    label: "관심",
    desc: "누군가 ‘가고 싶어요’를 누른 발견 핀",
    color: PIN_COLORS.reel,
    Glyph: () => <InterestBadgeIcon size={14} />,
  },
  {
    label: "위시",
    desc: "그룹원 과반이 가고 싶어해 승급한 곳",
    color: PIN_COLORS.wish,
    Glyph: () => <WishGlyph size={14} color={PIN_COLORS.wish} />,
  },
  {
    label: "추억",
    desc: "방문 후 추억으로 기록된 곳",
    color: PIN_COLORS.memory,
    Glyph: () => <MemoryGlyph w={14} h={14} color={PIN_COLORS.memory} />,
  },
];

export function TagLegendButton() {
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

  return (
    <div ref={wrapRef} style={{ position: "relative" }}>
      <button
        type="button"
        aria-label="아이콘 및 단계 안내"
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
        }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="10" fill="none" stroke={colors.inkSoft} strokeWidth="1.6" />
          <rect x="11" y="7" width="2" height="6.5" rx="1" fill={colors.inkSoft} />
          <circle cx="12" cy="16.5" r="1.1" fill={colors.inkSoft} />
        </svg>
      </button>

      {open && (
        <div
          role="tooltip"
          style={{
            position: "absolute",
            bottom: 52,
            left: 0,
            background: colors.panel,
            borderRadius: 16,
            boxShadow: `0 8px 28px rgba(0,0,0,0.14)`,
            border: `1px solid ${colors.hairline}`,
            padding: "14px 16px 12px",
            minWidth: 248,
            maxWidth: 280,
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
              margin: "0 0 6px",
              letterSpacing: 0.3,
            }}
          >
            핀은 이렇게 자라요
          </p>
          <p
            style={{
              fontSize: 11.5,
              color: colors.inkSoft,
              margin: "0 0 12px",
              lineHeight: 1.5,
            }}
          >
            ‘가고 싶어요’ 과반이면 <strong style={{ color: colors.ink }}>위시</strong>,
            방문하면 <strong style={{ color: colors.ink }}>추억</strong>이 돼요.
          </p>

          {STAGES.map(({ label, desc, color, Glyph }, idx) => (
            <div
              key={label}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 10,
                marginBottom: idx === STAGES.length - 1 ? 0 : 8,
              }}
            >
              <div
                style={{
                  width: 28,
                  height: 28,
                  borderRadius: "50%",
                  background: `${color}1A`,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  flexShrink: 0,
                }}
              >
                <Glyph />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    fontSize: 12.5,
                    fontWeight: 700,
                    color: colors.ink,
                    lineHeight: 1.3,
                  }}
                >
                  {label}
                </div>
                <div
                  style={{
                    fontSize: 11,
                    color: colors.inkSoft,
                    lineHeight: 1.4,
                    marginTop: 2,
                  }}
                >
                  {desc}
                </div>
              </div>
            </div>
          ))}

          <div
            style={{
              borderTop: `1px solid ${colors.hairline}`,
              marginTop: 12,
              paddingTop: 10,
              display: "flex",
              flexDirection: "column",
              gap: 8,
            }}
          >
            <p
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: colors.inkFaint,
                margin: 0,
                letterSpacing: 0.3,
              }}
            >
              하트(가고 싶어요)
            </p>
            <LegendRow
              glyph={<HeartIcon filled color="#FF2D55" />}
              title="채워진 하트"
              desc="내가 가고 싶다고 표시한 곳"
            />
            <LegendRow
              glyph={<HeartIcon color={colors.inkSoft} />}
              title="빈 하트"
              desc="누르면 ‘가고 싶어요’가 켜져요"
            />
          </div>
        </div>
      )}
    </div>
  );
}

function LegendRow({
  glyph,
  title,
  desc,
}: {
  glyph: React.ReactNode;
  title: string;
  desc: string;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <span
        style={{
          width: 28,
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
        }}
      >
        {glyph}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{
            fontFamily: fonts.sans,
            fontSize: 12,
            fontWeight: 700,
            color: colors.ink,
            lineHeight: 1.3,
          }}
        >
          {title}
        </div>
        <div
          style={{
            fontFamily: fonts.sans,
            fontSize: 10.5,
            color: colors.inkSoft,
            lineHeight: 1.4,
            marginTop: 2,
          }}
        >
          {desc}
        </div>
      </div>
    </div>
  );
}

function HeartIcon({
  filled = false,
  color,
}: {
  filled?: boolean;
  color: string;
}) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
      <path
        d="M12 21s-7.5-4.6-9.5-9.1C1 7.7 3.6 4 7.3 4c2 0 3.5 1.1 4.7 2.7C13.2 5.1 14.7 4 16.7 4c3.7 0 6.3 3.7 4.8 7.9C19.5 16.4 12 21 12 21z"
        fill={filled ? color : "none"}
        stroke={color}
        strokeWidth="1.8"
        strokeLinejoin="round"
      />
    </svg>
  );
}
