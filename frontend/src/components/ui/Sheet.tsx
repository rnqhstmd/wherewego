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
 * Bottom sheet — 모바일 전용. ActionBar(높이 64px)를 가리지 않도록
 * 기본 bottom 오프셋을 두어 항상 액션바가 노출되도록 한다.
 * 컨테이너 역할만 담당. 콘텐츠는 children 으로 주입.
 */
export function Sheet({ children, padTop = 6, className, style }: SheetProps) {
  return (
    <div
      className={className}
      style={{
        position: "absolute",
        bottom: 64,
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
