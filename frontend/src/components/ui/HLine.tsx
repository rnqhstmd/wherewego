import type { CSSProperties } from "react";
import { colors } from "@/lib/design/tokens";

interface HLineProps {
  className?: string;
  style?: CSSProperties;
}

/**
 * Hairline divider — tokens.jsx::HLine 1:1 변환.
 */
export function HLine({ className, style }: HLineProps) {
  return (
    <div
      className={className}
      style={{ height: 1, background: colors.hairline, ...style }}
    />
  );
}
