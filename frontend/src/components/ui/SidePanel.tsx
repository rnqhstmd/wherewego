import type { CSSProperties, ReactNode } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface SidePanelProps {
  title?: ReactNode;
  children: ReactNode;
  width?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * Desktop side panel — floating 카드 스타일.
 *
 * 좌측 사이드바와 일정 간격을 두고 둥근 모서리로 떠 있어 모바일 Sheet 와 통일된 룩을 갖는다.
 * 상하 12px floating margin, 둥근 모서리 20px, 부드러운 그림자.
 */
export function SidePanel({
  title,
  children,
  width = 320,
  className,
  style,
}: SidePanelProps) {
  return (
    <aside
      className={className}
      style={{
        position: "absolute",
        top: 12,
        bottom: 12,
        width,
        background: colors.panel,
        borderRadius: 20,
        border: `1px solid ${colors.hairline}`,
        boxShadow: `0 10px 28px ${colors.shadowMd}`,
        zIndex: 20,
        display: "flex",
        flexDirection: "column",
        fontFamily: fonts.sans,
        overflow: "hidden",
        ...style,
      }}
    >
      {title !== undefined && title !== null ? (
        <header
          style={{
            padding: "18px 22px 12px",
            borderBottom: `1px solid ${colors.hairline}`,
            fontFamily: fonts.sans,
            fontSize: 15,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -0.2,
            flexShrink: 0,
          }}
        >
          {title}
        </header>
      ) : null}
      <div style={{ flex: 1, overflowY: "auto", padding: "16px 22px 22px" }}>
        {children}
      </div>
    </aside>
  );
}
