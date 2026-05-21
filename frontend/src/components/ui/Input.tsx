"use client";

import type { ChangeEvent, CSSProperties, KeyboardEvent } from "react";
import { colors, fonts } from "@/lib/design/tokens";

interface InputProps {
  placeholder?: string;
  value?: string;
  onChange?: (value: string) => void;
  onKeyDown?: (event: KeyboardEvent<HTMLInputElement>) => void;
  className?: string;
  style?: CSSProperties;
  autoFocus?: boolean;
  disabled?: boolean;
  type?: "text" | "search";
  name?: string;
  id?: string;
  /**
   * iOS Safari 자동완성 chip(🔑/💳/📍) 차단을 위한 속성들.
   * 검색처럼 자동완성이 무의미한 input은 "off" 풀 조합으로 차단한다.
   * 메모처럼 자유 텍스트는 미지정(undefined) 유지.
   */
  autoComplete?: string;
  inputMode?:
    | "none"
    | "text"
    | "decimal"
    | "numeric"
    | "tel"
    | "search"
    | "email"
    | "url";
  enterKeyHint?:
    | "enter"
    | "done"
    | "go"
    | "next"
    | "previous"
    | "search"
    | "send";
  autoCorrect?: "on" | "off";
  autoCapitalize?: "on" | "off" | "sentences" | "words" | "characters" | "none";
  spellCheck?: boolean;
}

/**
 * Input field — tokens.jsx::Input 을 controlled input 으로 확장.
 * 원본은 placeholder만 표시하는 mock 이지만,
 * 실 사용을 위해 value/onChange 를 받는다.
 */
export function Input({
  placeholder,
  value,
  onChange,
  onKeyDown,
  className,
  style,
  autoFocus,
  disabled = false,
  type = "text",
  name,
  id,
  autoComplete,
  inputMode,
  enterKeyHint,
  autoCorrect,
  autoCapitalize,
  spellCheck,
}: InputProps) {
  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    onChange?.(event.target.value);
  };

  return (
    <div
      className={className}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 8,
        border: `1.5px solid ${colors.hairline}`,
        borderRadius: 10,
        padding: "10px 14px",
        background: colors.bg,
        fontFamily: fonts.sans,
        fontSize: 14,
        color: colors.inkSoft,
        ...style,
      }}
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke={colors.inkFaint}
        strokeWidth="2"
        strokeLinecap="round"
        aria-hidden="true"
      >
        <circle cx="11" cy="11" r="7" />
        <line x1="16.5" y1="16.5" x2="21" y2="21" />
      </svg>
      <input
        id={id}
        name={name}
        type={type}
        placeholder={placeholder}
        value={value}
        onChange={handleChange}
        onKeyDown={onKeyDown}
        autoFocus={autoFocus}
        disabled={disabled}
        autoComplete={autoComplete}
        inputMode={inputMode}
        enterKeyHint={enterKeyHint}
        autoCorrect={autoCorrect}
        autoCapitalize={autoCapitalize}
        spellCheck={spellCheck}
        style={{
          flex: 1,
          border: "none",
          outline: "none",
          background: "transparent",
          fontFamily: "inherit",
          fontSize: "inherit",
          color: colors.ink,
          padding: 0,
          minWidth: 0,
        }}
      />
    </div>
  );
}
