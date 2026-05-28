"use client";

import { useEffect, useRef, useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import {
  MemoryGlyph,
  PIN_COLORS,
  ReelGlyph,
  WishGlyph,
} from "@/lib/pin/markers";

/**
 * 좌측 하단 ! 아이콘 버튼.
 * 클릭 시 핀 마커 3종(발견/위시/추억)의 의미를 통합 표시한다.
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
    desc: "둘러본 곳",
    color: PIN_COLORS.reel,
    Glyph: () => <ReelGlyph size={14} color={PIN_COLORS.reel} />,
  },
  {
    label: "위시",
    desc: "가고 싶다고 표시한 곳",
    color: PIN_COLORS.wish,
    Glyph: () => <WishGlyph size={14} color={PIN_COLORS.wish} />,
  },
  {
    label: "추억",
    desc: "다녀온 곳",
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
            지도 마커 안내
          </p>
          <p
            style={{
              fontSize: 11.5,
              color: colors.inkSoft,
              margin: "0 0 12px",
              lineHeight: 1.5,
            }}
          >
            가고 싶은 곳은 <strong style={{ color: colors.ink }}>위시</strong>로,
            다녀오면 <strong style={{ color: colors.ink }}>추억</strong>이 돼요.
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
        </div>
      )}
    </div>
  );
}
