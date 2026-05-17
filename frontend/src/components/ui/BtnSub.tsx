"use client";

import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface BtnSubProps {
  children: ReactNode;
  onClick?: ButtonHTMLAttributes<HTMLButtonElement>["onClick"];
  className?: string;
  style?: CSSProperties;
  disabled?: boolean;
  type?: "button" | "submit" | "reset";
}

/**
 * Ghost / secondary 버튼 — tokens.jsx::BtnSub 1:1 변환.
 * 취소 등 보조 행동에 사용.
 */
export function BtnSub({
  children,
  onClick,
  className,
  style,
  disabled = false,
  type = "button",
}: BtnSubProps) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={className}
      style={{
        background: "transparent",
        color: colors.ctaSub,
        border: `1.5px solid ${colors.hairline}`,
        borderRadius: 8,
        padding: "11px 20px",
        fontFamily: fonts.sans,
        fontSize: 14,
        fontWeight: 500,
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.5 : 1,
        whiteSpace: "nowrap",
        ...style,
      }}
    >
      {children}
    </button>
  );
}
