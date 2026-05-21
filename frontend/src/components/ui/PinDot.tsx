import type { CSSProperties } from "react";
import { ReelGlyph, WishGlyph, MemoryGlyph } from "@/lib/pin/markers";

export type PinDotType = "reel" | "wish" | "memory";

interface PinDotProps {
  type?: PinDotType;
  size?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * Map pin marker — Phase 7 태그 3종(REEL/WISH/MEMORY) 글리프.
 *
 * `@/lib/pin/markers`의 React 글리프(ReelGlyph/WishGlyph/MemoryGlyph)에
 * 1:1로 위임한다. MapboxView의 vanilla DOM 마커(`getXxxSvgString`)와
 * 동일한 SVG markup을 공유한다.
 *
 * M1 fallback: 알 수 없는 타입은 WishGlyph로 폴백.
 */
export function PinDot({
  type = "wish",
  size = 10,
  className,
  style,
}: PinDotProps) {
  const wrapperStyle: CSSProperties | undefined =
    className || style ? { display: "inline-flex", flexShrink: 0, ...style } : undefined;

  let glyph;
  switch (type) {
    case "reel":
      glyph = <ReelGlyph size={size} />;
      break;
    case "memory":
      glyph = <MemoryGlyph w={size * 1.5} h={size * 1.3} />;
      break;
    case "wish":
      glyph = <WishGlyph size={size} />;
      break;
    default:
      // M1 fallback: 알 수 없는 enum → WISH 글리프
      // Phase 7 사용자 확인된 안전장치 — 운영 관찰 목적
      console.warn("[PinDot] Unknown PinDotType, falling back to wish:", type);
      glyph = <WishGlyph size={size} />;
      break;
  }

  if (!className && !style) {
    return glyph;
  }
  return (
    <span className={className} style={wrapperStyle} data-tag={type}>
      {glyph}
    </span>
  );
}
