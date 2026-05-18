"use client";

import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface BtnKakaoProps {
  children: ReactNode;
  onClick?: ButtonHTMLAttributes<HTMLButtonElement>["onClick"];
  className?: string;
  style?: CSSProperties;
  disabled?: boolean;
  type?: "button" | "submit" | "reset";
}

/**
 * 카카오 로그인 버튼 — tokens.jsx::BtnKakao 1:1 변환.
 * 노란 배경 + 카카오 잉크색 텍스트.
 */
export function BtnKakao({
  children,
  onClick,
  className,
  style,
  disabled = false,
  type = "button",
}: BtnKakaoProps) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={className}
      style={{
        background: colors.kakao,
        color: colors.kakaoInk,
        border: "none",
        borderRadius: 12,
        padding: "15px 28px",
        fontFamily: fonts.sans,
        fontSize: 16,
        fontWeight: 700,
        display: "flex",
        alignItems: "center",
        gap: 10,
        width: "100%",
        justifyContent: "center",
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.5 : 1,
        ...style,
      }}
    >
      {children}
    </button>
  );
}
