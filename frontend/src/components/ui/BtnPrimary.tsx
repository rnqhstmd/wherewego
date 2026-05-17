"use client";

import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface BtnPrimaryProps {
  children: ReactNode;
  onClick?: ButtonHTMLAttributes<HTMLButtonElement>["onClick"];
  className?: string;
  style?: CSSProperties;
  disabled?: boolean;
  type?: "button" | "submit" | "reset";
}

/**
 * Primary rust 버튼 — tokens.jsx::BtnPrimary 1:1 변환.
 * 완료/저장 등 주요 행동에 사용.
 */
export function BtnPrimary({
  children,
  onClick,
  className,
  style,
  disabled = false,
  type = "button",
}: BtnPrimaryProps) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={className}
      style={{
        background: colors.cta,
        color: "#fff",
        border: "none",
        borderRadius: 8,
        padding: "11px 20px",
        fontFamily: fonts.sans,
        fontSize: 14,
        fontWeight: 600,
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
