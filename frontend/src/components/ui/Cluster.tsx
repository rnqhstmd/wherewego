import type { CSSProperties } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface ClusterProps {
  n?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * Cluster bubble "○ N" — tokens.jsx::Cluster 1:1 변환.
 * 32px 흰 원 + 숫자.
 */
export function Cluster({ n = 3, className, style }: ClusterProps) {
  return (
    <div
      className={className}
      style={{
        width: 32,
        height: 32,
        borderRadius: "50%",
        background: colors.panel,
        border: `2px solid ${colors.hairline}`,
        boxShadow: `0 2px 8px ${colors.shadow}`,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontFamily: fonts.sans,
        fontSize: 12,
        fontWeight: 700,
        color: colors.ink,
        ...style,
      }}
    >
      {n}
    </div>
  );
}
