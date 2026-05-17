import type { CSSProperties, ReactNode } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface PanelLabelProps {
  children: ReactNode;
  className?: string;
  style?: CSSProperties;
}

/**
 * Section label above panel rows — tokens.jsx::PanelLabel 1:1 변환.
 */
export function PanelLabel({ children, className, style }: PanelLabelProps) {
  return (
    <div
      className={className}
      style={{
        fontFamily: fonts.sans,
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: ".08em",
        textTransform: "uppercase",
        color: colors.inkSoft,
        marginBottom: 8,
        ...style,
      }}
    >
      {children}
    </div>
  );
}
