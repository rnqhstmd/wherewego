"use client";

import type { ButtonHTMLAttributes, CSSProperties, JSX } from "react";
import type { PinTag as PinTagValue } from "@/lib/api/types";
import { fonts } from "@/lib/design/tokens";
import { ReelGlyph, WishGlyph, MemoryGlyph } from "@/lib/pin/markers";

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
 * Tailwind v4 정적 클래스 매핑.
 *
 * 동적 문자열 보간(`bg-pin-${k}/10`)은 Tailwind JIT가 인식하지 못하므로
 * 타입별 클래스 문자열을 정적으로 풀어서 매핑한다.
 *
 * 색상 식별성을 위해 inactive에서도 자기 색을 100% 알파로 사용한다
 * (옅은 채도의 WISH/REEL이 30% 투명도에서 흰 배경에 사라지는 문제 해결).
 */
const TAG_CLASSES: Record<PinTagValue, { active: string; inactive: string }> = {
  REEL: {
    active: "bg-pin-reel text-white border-pin-reel",
    inactive: "text-pin-reel border-pin-reel bg-transparent",
  },
  WISH: {
    active: "bg-pin-wish text-white border-pin-wish",
    inactive: "text-pin-wish border-pin-wish bg-transparent",
  },
  MEMORY: {
    active: "bg-pin-memory text-white border-pin-memory",
    inactive: "text-pin-memory border-pin-memory bg-transparent",
  },
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
  const classes = TAG_CLASSES[type];
  if (!meta || !classes) {
    // M1 fallback: 알 수 없는 enum → WISH 메타로 폴백.
    // Phase 7 사용자 확인된 안전장치 — 운영 관찰 목적
    console.warn("[PinTag] Unknown PinTag value, falling back to WISH:", type);
  }
  const resolvedMeta = meta ?? TAG_META.WISH;
  const resolvedClasses = classes ?? TAG_CLASSES.WISH;
  const colorClasses = active ? resolvedClasses.active : resolvedClasses.inactive;
  const Glyph = resolvedMeta.Glyph;

  const composedClassName = [
    "inline-flex items-center gap-1.5",
    colorClasses,
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
      <Glyph size={10} color={active ? "white" : undefined} />
      {resolvedMeta.label}
    </button>
  );
}
