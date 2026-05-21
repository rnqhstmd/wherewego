"use client";

import type { ButtonHTMLAttributes, CSSProperties, JSX } from "react";
import type { PinTag as PinTagValue } from "@/lib/api/types";
import { fonts } from "@/lib/design/tokens";
import { ReelGlyph, WishGlyph, MemoryGlyph, PIN_COLORS } from "@/lib/pin/markers";

interface PinTagProps {
  type?: PinTagValue;
  active?: boolean;
  onClick?: ButtonHTMLAttributes<HTMLButtonElement>["onClick"];
  className?: string;
  style?: CSSProperties;
  disabled?: boolean;
}

interface TagMeta {
  label: string;
  Glyph: (props: { size: number; color?: string }) => JSX.Element;
}

const TAG_META: Record<PinTagValue, TagMeta> = {
  REEL: {
    label: "발견",
    Glyph: ({ size, color }) => <ReelGlyph size={size} color={color} />,
  },
  WISH: {
    label: "위시",
    Glyph: ({ size, color }) => <WishGlyph size={size} color={color} />,
  },
  MEMORY: {
    label: "추억",
    Glyph: ({ size, color }) => <MemoryGlyph w={size} h={size} color={color} />,
  },
};

/**
 * PinTagValue → markers.tsx의 PIN_COLORS 키 매핑.
 * Tailwind v4 클래스(`bg-pin-{kind}`)가 dev hot reload 캐시 등으로
 * 누락되는 케이스가 있어, 본 컴포넌트는 색을 인라인 style로 직접 명시한다.
 */
const TAG_COLOR_HEX: Record<PinTagValue, string> = {
  REEL: PIN_COLORS.reel,
  WISH: PIN_COLORS.wish,
  MEMORY: PIN_COLORS.memory,
};

/**
 * Tag chip — Phase 7 태그 3종(REEL/WISH/MEMORY) 칩.
 *
 * 이모지 폐기, `@/lib/pin/markers` SVG 글리프로 통일. 색상은 Tailwind v4
 * `pin-{kind}` 토큰 클래스로 active/inactive 전환.
 *
 * M1 fallback: 알 수 없는 enum은 WISH 메타로 폴백.
 */
export function PinTag({
  type = "WISH",
  active = false,
  onClick,
  className,
  style,
  disabled = false,
}: PinTagProps) {
  const meta = TAG_META[type];
  const colorHex = TAG_COLOR_HEX[type];
  if (!meta || !colorHex) {
    // M1 fallback: 알 수 없는 enum → WISH 메타로 폴백.
    // Phase 7 사용자 확인된 안전장치 — 운영 관찰 목적
    console.warn("[PinTag] Unknown PinTag value, falling back to WISH:", type);
  }
  const resolvedMeta = meta ?? TAG_META.WISH;
  const resolvedHex = colorHex ?? TAG_COLOR_HEX.WISH;
  const Glyph = resolvedMeta.Glyph;

  // inactive 배경 — 자기 색 8% (hex 8자리: 0x14 ≈ 8/100). 식별성 보조.
  const inactiveBg = `${resolvedHex}14`;

  const composedClassName = [
    "inline-flex items-center gap-1.5",
    className ?? "",
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={composedClassName}
      data-active={active ? "true" : "false"}
      data-tag={type}
      style={{
        backgroundColor: active ? resolvedHex : inactiveBg,
        color: active ? "#FFFFFF" : resolvedHex,
        borderColor: resolvedHex,
        borderStyle: "solid",
        borderWidth: 1.5,
        borderRadius: 999,
        padding: "7px 16px",
        fontFamily: fonts.sans,
        fontSize: 13,
        fontWeight: 600,
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.5 : 1,
        ...style,
      }}
    >
      <Glyph size={10} color={active ? "#FFFFFF" : resolvedHex} />
      {resolvedMeta.label}
    </button>
  );
}
