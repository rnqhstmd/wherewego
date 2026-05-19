// Bell 아이콘 — tokens.jsx::IconBell 1:1 변환.
// 알림 권한 다이얼로그 등에서 사용.

import type { CSSProperties } from "react";

interface IconProps {
  size?: number;
  color?: string;
  className?: string;
  style?: CSSProperties;
}

/** 종 모양 알림 아이콘 — tokens.jsx::IconBell 1:1 */
export function IconBell({
  size = 24,
  color = "currentColor",
  className,
  style,
}: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={color}
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      style={style}
      aria-hidden="true"
    >
      <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
      <path d="M13.7 21a2 2 0 0 1-3.4 0" />
    </svg>
  );
}
