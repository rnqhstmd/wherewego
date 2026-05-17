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
 * Bottom sheet — screens-mobile.jsx::Sheet 1:1 변환.
 * 콘텐츠 크기, 화면 하단(액션 바 위) 정렬.
 * 컨테이너 역할만 담당. 콘텐츠는 children 으로 주입.
 */
export function Sheet({ children, padTop = 6, className, style }: SheetProps) {
  return (
    <div
      className={className}
      style={{
        position: "absolute",
        bottom: 0,
        left: 0,
        right: 0,
        background: colors.panel,
        borderTopLeftRadius: 20,
        borderTopRightRadius: 20,
        boxShadow: `0 -4px 24px ${colors.shadowMd}`,
        zIndex: 20,
        paddingTop: padTop,
        ...style,
      }}
    >
      <DragHandle />
      <div style={{ padding: "14px 20px 20px" }}>{children}</div>
    </div>
  );
}
