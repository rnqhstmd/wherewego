import type { CSSProperties, ReactNode } from "react";
import { colors } from "@/lib/design/tokens";
import { DragHandle } from "./DragHandle";

interface SheetProps {
  children: ReactNode;
  padTop?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * Bottom sheet — 모바일 전용 floating 카드.
 *
 * 하단 ActionBar(bottom:12, height:68) 위로 8px 간격을 두고 떠 있는 형태.
 * 좌우 12px 여백 + 전 모서리 둥근 라운드 + 부드러운 그림자로 ActionBar 와 통일된 floating 룩.
 */
export function Sheet({ children, padTop = 6, className, style }: SheetProps) {
  return (
    <div
      className={className}
      style={{
        position: "absolute",
        bottom: 88,
        left: 12,
        right: 12,
        background: colors.panel,
        borderRadius: 20,
        border: `1px solid ${colors.hairline}`,
        boxShadow: `0 10px 28px ${colors.shadowMd}`,
        zIndex: 20,
        paddingTop: padTop,
        overflow: "hidden",
        ...style,
      }}
    >
      <DragHandle />
      <div style={{ padding: "12px 18px 18px" }}>{children}</div>
    </div>
  );
}
