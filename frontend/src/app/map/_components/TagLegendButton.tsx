"use client";

import { useEffect, useRef, useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { MemoryGlyph, PIN_COLORS, ReelGlyph, WishGlyph } from "@/lib/pin/markers";

const TAGS = [
  {
    label: "추억",
    desc: "함께 다녀온 소중한 곳",
    color: PIN_COLORS.memory,
    Glyph: () => <MemoryGlyph w={15} h={15} color={PIN_COLORS.memory} />,
  },
  {
    label: "위시",
    desc: "언젠가 함께 가고 싶은 곳",
    color: PIN_COLORS.wish,
    Glyph: () => <WishGlyph size={15} color={PIN_COLORS.wish} />,
  },
  {
    label: "발견",
    desc: "인스타에서 발견한 가고 싶은 곳",
    color: PIN_COLORS.reel,
    Glyph: () => <ReelGlyph size={15} color={PIN_COLORS.reel} />,
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
      {/* 로고/프로필 버튼과 동일한 44px 원형 스타일 */}
      <button
        type="button"
        aria-label="태그 안내"
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
        {/* ! 아이콘 */}
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="10" fill="none" stroke={colors.inkSoft} strokeWidth="1.6" />
          <rect x="11" y="7" width="2" height="6.5" rx="1" fill={colors.inkSoft} />
          <circle cx="12" cy="16.5" r="1.1" fill={colors.inkSoft} />
        </svg>
      </button>

      {/* 말풍선 — 버튼 오른쪽 위로 열림, 꼬리는 좌하단 */}
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
            padding: "14px 16px 10px",
            minWidth: 216,
            zIndex: 60,
            fontFamily: fonts.sans,
            animation: "maygo-bubble-pop 200ms cubic-bezier(0.2,0.8,0.2,1) both",
          }}
        >
          {/* 말풍선 꼬리 — 하단 왼쪽, 아래 방향 삼각형 */}
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
              marginBottom: 10,
              letterSpacing: 0.3,
            }}
          >
            각 태그는 이런 의미예요
          </p>

          {TAGS.map(({ label, desc, color, Glyph }) => (
            <div
              key={label}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 10,
                marginBottom: 8,
              }}
            >
              <div
                style={{
                  width: 30,
                  height: 30,
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
              <div>
                <div
                  style={{
                    fontSize: 13,
                    fontWeight: 600,
                    color: colors.ink,
                    lineHeight: 1.3,
                  }}
                >
                  {label}
                </div>
                <div
                  style={{
                    fontSize: 11.5,
                    color: colors.inkSoft,
                    lineHeight: 1.4,
                    marginTop: 1,
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
