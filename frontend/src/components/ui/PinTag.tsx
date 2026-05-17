"use client";

import type { ButtonHTMLAttributes, CSSProperties } from "react";
import { colors, fonts } from "@/lib/design/tokens";

export type PinTagType = "place" | "memory";

interface PinTagProps {
  type?: PinTagType;
  active?: boolean;
  onClick?: ButtonHTMLAttributes<HTMLButtonElement>["onClick"];
  className?: string;
  style?: CSSProperties;
  disabled?: boolean;
}

/**
 * Tag chip — tokens.jsx::PinTag 1:1 변환.
 * "● 장소" / "♡ 추억" 칩. active 시 채워진 색 + 흰 글자.
 */
export function PinTag({
  type = "place",
  active = false,
  onClick,
  className,
  style,
  disabled = false,
}: PinTagProps) {
  const isPlace = type === "place";
  const color = isPlace ? colors.pinPlace : colors.pinMemory;
  const label = isPlace ? "● 장소" : "♡ 추억";

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={className}
      data-active={active ? "true" : "false"}
      data-tag={type}
      style={{
        background: active ? color : "transparent",
        color: active ? "#fff" : colors.ink,
        border: `1.5px solid ${active ? color : colors.hairline}`,
        borderRadius: 999,
        padding: "7px 16px",
        fontFamily: fonts.sans,
        fontSize: 13,
        fontWeight: 600,
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.5 : 1,
        ...style,
      }}
    >
      {label}
    </button>
  );
}
