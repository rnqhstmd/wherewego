import type { CSSProperties, ReactNode } from "react";
import { colors } from "@/lib/design/tokens";
import { DragHandle } from "./DragHandle";

interface SheetProps {
  children: ReactNode;
  padTop?: number;
  /**
   * 하단 ActionBar 위 간격(px). 키보드 시 ActionBar 가 unmount 되면 호출자가
   * 12 로 낮춰 ActionBar 자리까지 시트가 확장되도록 한다. 미주입 시 88 (기본 floating).
   */
  bottomOffset?: number;
  /**
   * 시트의 최대 높이. 주입 시 내부 콘텐츠 영역이 {@code overflow-y: auto} 로 전환되어
   * 키보드 등장으로 가용 공간이 줄어든 상황에서도 폼 전체가 잘리지 않는다.
   * 미주입 시 컨텐츠 크기에 자동 맞춤 (기존 동작 유지).
   */
  maxHeight?: number | string;
  className?: string;
  style?: CSSProperties;
}

/**
 * Bottom sheet — 모바일 전용 floating 카드.
 *
 * 하단 ActionBar(bottom:12, height:68) 위로 8px 간격을 두고 떠 있는 형태.
 * 좌우 12px 여백 + 전 모서리 둥근 라운드 + 부드러운 그림자로 ActionBar 와 통일된 floating 룩.
 *
 * <p>{@link SheetProps#maxHeight} 와 {@link SheetProps#bottomOffset} 을 함께 사용하면
 * 키보드 등장 시 ActionBar 자리(76px)까지 시트를 확장하면서 내부 스크롤을 활성화할 수 있다.</p>
 */
export function Sheet({
  children,
  padTop = 6,
  bottomOffset = 88,
  maxHeight,
  className,
  style,
}: SheetProps) {
  const scrollable = maxHeight !== undefined;
  return (
    <div
      className={className}
      style={{
        position: "absolute",
        bottom: bottomOffset,
        left: 12,
        right: 12,
        background: colors.panel,
        borderRadius: 20,
        border: `1px solid ${colors.hairline}`,
        boxShadow: `0 10px 28px ${colors.shadowMd}`,
        zIndex: 20,
        paddingTop: padTop,
        overflow: "hidden",
        ...(scrollable
          ? {
              maxHeight,
              display: "flex",
              flexDirection: "column",
            }
          : {}),
        ...style,
      }}
    >
      <DragHandle />
      <div
        style={{
          padding: "12px 18px 18px",
          ...(scrollable
            ? { overflowY: "auto", flex: 1, minHeight: 0 }
            : {}),
        }}
      >
        {children}
      </div>
    </div>
  );
}
