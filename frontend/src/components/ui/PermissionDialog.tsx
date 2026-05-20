"use client";

import type { CSSProperties, ReactNode } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { BtnPrimary } from "./BtnPrimary";
import { BtnSub } from "./BtnSub";

interface PermissionDialogProps {
  title: string;
  /** 본문 설명 (줄바꿈은 \n 사용) */
  description: string;
  primaryLabel: string;
  secondaryLabel: string;
  onPrimary: () => void;
  onSecondary: () => void;
  icon?: ReactNode;
  /**
   * 아이콘 원형 배경의 opacity (rust 톤, 기본 0.08).
   * AC-011 등에서 강조가 필요한 호출처는 0.15 로 명시한다.
   */
  iconBgOpacity?: number;
  /** 'vertical' 권장(위치 권한 등 모달), 'horizontal' (보조 액션) */
  layout?: "vertical" | "horizontal";
  /** 지도 위 오버레이(딤 배경) 여부 */
  onMap?: boolean;
  className?: string;
  style?: CSSProperties;
}

/**
 * Permission dialog — screens-basic.jsx::PermissionDialog 1:1 변환.
 *
 * - 흰 카드(border-radius 18px, max-width 320px)
 * - 상단 60px 원형 아이콘 박스 (rust 톤, opacity 는 iconBgOpacity 로 제어, 기본 8%)
 * - 제목(Gowun Batang 22px) + 설명(13.5px inkSoft)
 * - 버튼 스택(vertical: 위 primary / 아래 sub, horizontal: 좌 sub / 우 primary)
 */
export function PermissionDialog({
  title,
  description,
  primaryLabel,
  secondaryLabel,
  onPrimary,
  onSecondary,
  icon,
  iconBgOpacity = 0.08,
  layout = "vertical",
  onMap = false,
  className,
  style,
}: PermissionDialogProps) {
  return (
    <div
      className={className}
      role="dialog"
      aria-modal="true"
      aria-label={title}
      style={{
        position: "absolute",
        inset: 0,
        background: onMap ? "rgba(26,26,46,0.45)" : "transparent",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 20,
        zIndex: 30,
        ...style,
      }}
    >
      <div
        style={{
          background: colors.panel,
          borderRadius: 18,
          padding: "28px 24px 20px",
          width: "100%",
          maxWidth: 320,
          boxShadow: `0 10px 32px ${colors.shadowMd}`,
          textAlign: "center",
          fontFamily: fonts.sans,
        }}
      >
        {icon ? (
          <div
            style={{
              width: 60,
              height: 60,
              borderRadius: "50%",
              // rust 톤 (196,98,45) + iconBgOpacity (기본 0.08, AC-011 호출처는 0.15)
              background: `rgba(196,98,45,${iconBgOpacity})`,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              margin: "0 auto 18px",
            }}
          >
            {icon}
          </div>
        ) : null}

        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 22,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -0.5,
            marginBottom: 10,
          }}
        >
          {title}
        </div>
        <div
          style={{
            fontSize: 13.5,
            color: colors.inkSoft,
            lineHeight: 1.6,
            marginBottom: 24,
            whiteSpace: "pre-line",
          }}
        >
          {description}
        </div>

        {layout === "horizontal" ? (
          <div style={{ display: "flex", gap: 8 }}>
            <BtnSub
              style={{ flex: 1, padding: "11px 0" }}
              onClick={onSecondary}
            >
              {secondaryLabel}
            </BtnSub>
            <BtnPrimary
              style={{ flex: 1.4, padding: "11px 0" }}
              onClick={onPrimary}
            >
              {primaryLabel}
            </BtnPrimary>
          </div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <BtnPrimary
              style={{ padding: "12px 0" }}
              onClick={onPrimary}
            >
              {primaryLabel}
            </BtnPrimary>
            <BtnSub
              style={{ padding: "11px 0" }}
              onClick={onSecondary}
            >
              {secondaryLabel}
            </BtnSub>
          </div>
        )}
      </div>
    </div>
  );
}
