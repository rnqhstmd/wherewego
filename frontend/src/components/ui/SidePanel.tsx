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
 * Desktop side panel — design.md §7/§8 사양 기반.
 * - width 기본 280px
 * - 좌측에서 펼쳐지는 흰 패널 + 우측 그림자
 * - 헤더(title) + 콘텐츠 children
 * 컨테이너 역할만 담당. 콘텐츠는 children 으로 주입.
 */
export function SidePanel({
  title,
  children,
  width = 280,
  className,
  style,
}: SidePanelProps) {
  return (
    <aside
      className={className}
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        bottom: 0,
        width,
        background: colors.panel,
        boxShadow: `3px 0 16px ${colors.shadow}`,
        zIndex: 20,
        display: "flex",
        flexDirection: "column",
        fontFamily: fonts.sans,
        ...style,
      }}
    >
      {title !== undefined && title !== null ? (
        <header
          style={{
            padding: "20px 22px 14px",
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
      <div style={{ flex: 1, overflowY: "auto", padding: "18px 22px 22px" }}>
        {children}
      </div>
    </aside>
  );
}
