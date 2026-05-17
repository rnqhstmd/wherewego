import type { CSSProperties } from "react";
import { colors } from "@/lib/design/tokens";

export type PinDotType = "place" | "memory" | "new";

interface PinDotProps {
  type?: PinDotType;
  size?: number;
  ring?: boolean;
  className?: string;
  style?: CSSProperties;
}

/**
 * Map pin marker — tokens.jsx::PinDot 1:1 변환.
 * place/new: 동그라미 div. memory: 분홍 하트 SVG (viewBox="-8 -6 16 12").
 */
export function PinDot({
  type = "place",
  size = 10,
  ring = false,
  className,
  style,
}: PinDotProps) {
  if (type === "memory") {
    const w = size * 1.5;
    const h = size * 1.3;
    return (
      <svg
        width={w}
        height={h}
        viewBox="-8 -6 16 12"
        className={className}
        data-testid="pin-dot-memory"
        data-tag="memory"
        style={{
          flexShrink: 0,
          filter: `drop-shadow(0 1px 3px ${colors.pinMemory}80)`,
          ...style,
        }}
      >
        <path
          d="M 0 4.5 C -7 0 -8 -5 -3.5 -5 C -1.5 -5 0 -3 0 -3 C 0 -3 1.5 -5 3.5 -5 C 8 -5 7 0 0 4.5 Z"
          fill={colors.pinMemory}
        />
      </svg>
    );
  }

  const color = type === "new" ? colors.pinNew : colors.pinPlace;
  return (
    <div
      className={className}
      data-testid={type === "new" ? "pin-dot-new" : "pin-dot-place"}
      data-tag={type}
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        background: color,
        boxShadow: ring
          ? `0 0 0 3px ${color}40, 0 2px 6px ${color}60`
          : `0 1px 4px ${color}80`,
        flexShrink: 0,
        ...style,
      }}
    />
  );
}
