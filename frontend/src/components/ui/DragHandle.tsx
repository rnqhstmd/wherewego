import type { CSSProperties } from "react";
import { colors } from "@/lib/design/tokens";

interface DragHandleProps {
  className?: string;
  style?: CSSProperties;
}

/**
 * Drag handle bar (mobile bottom sheets) — tokens.jsx::DragHandle 1:1 변환.
 */
export function DragHandle({ className, style }: DragHandleProps) {
  return (
    <div
      className={className}
      style={{
        width: 36,
        height: 4,
        borderRadius: 2,
        background: colors.inkFaint,
        margin: "12px auto 0",
        ...style,
      }}
    />
  );
}
