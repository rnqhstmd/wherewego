"use client";

import type { PinSummaryResponse } from "@/lib/api/types";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { PinDot, type PinDotType } from "@/components/ui/PinDot";
import { colors, fonts } from "@/lib/design/tokens";

interface VisitToastProps {
  pin: PinSummaryResponse;
  onSkip: () => void;
  onConfirm: () => void;
}

/**
 * Phase 10 — 장소 방문 감지 토스트.
 *
 * 디자인: PinPopup(SpeechBubblePopup) 톤 그대로 — 메모, 장소+주소, 날짜+written by 를
 * 동일한 폰트/사이즈/순서로 표시한다. 인용부호 없이 자연 텍스트.
 *
 * 위치: 데스크탑/모바일 모두 화면 정중앙. max-width 380.
 * mount 시 페이드인 + scale 200ms ease-out. 자동 닫힘 없음.
 * `role="status"` 로 스크린리더에 변경을 알린다.
 */
export default function VisitToast({ pin, onSkip, onConfirm }: VisitToastProps) {
  const hasMemo = pin.memo != null && pin.memo.trim().length > 0;
  const hasAddress = pin.address != null && pin.address.length > 0;
  const authorLabel = pin.createdByNickname ?? String(pin.createdBy);
  const dateLabel = formatDate(pin.createdAt);
  const pinDotType: PinDotType =
    pin.tag === "REEL" ? "reel" : pin.tag === "MEMORY" ? "memory" : "wish";

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: "fixed",
        top: "50%",
        left: "50%",
        transform: "translate(-50%, -50%)",
        width: "calc(100% - 24px)",
        maxWidth: 380,
        zIndex: 25,
        background: colors.panel,
        borderRadius: 16,
        padding: "16px 18px 14px",
        boxShadow: `0 10px 28px ${colors.shadowMd}`,
        border: `1px solid ${colors.hairline}`,
        fontFamily: fonts.sans,
        animation: "maygo-visit-toast-fade-in 200ms ease-out both",
      }}
    >
      {/* 헤더 — 짧고 감성적 카피. 장소명은 본문에서 PinDot 과 함께 표시. */}
      <div
        style={{
          fontSize: 13,
          fontWeight: 600,
          color: colors.cta,
          marginBottom: 14,
          letterSpacing: -0.1,
        }}
      >
        🌸 함께 방문하셨나요?
      </div>

      {/* 메모 — PinPopup 과 동일 톤 (큰 글씨, 자연 텍스트). */}
      {hasMemo && (
        <div
          style={{
            fontSize: 15,
            fontWeight: 500,
            color: colors.ink,
            lineHeight: 1.5,
            letterSpacing: -0.2,
            marginBottom: 12,
            wordBreak: "break-word",
            whiteSpace: "pre-wrap",
          }}
        >
          {pin.memo}
        </div>
      )}

      {/* 장소 + 주소 — PinPopup 스타일 (PinDot + 굵은 장소명 + mono 주소). */}
      <div style={{ marginBottom: 4 }}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 7,
            marginBottom: 3,
          }}
        >
          <PinDot type={pinDotType} size={pinDotType === "memory" ? 11 : 8} />
          <span
            style={{
              fontSize: 13.5,
              fontWeight: 700,
              color: colors.ink,
              letterSpacing: -0.2,
            }}
          >
            {pin.placeName}
          </span>
        </div>
        {hasAddress && (
          <div
            style={{
              fontFamily: fonts.mono,
              fontSize: 11.5,
              color: colors.inkSoft,
              letterSpacing: -0.1,
              paddingLeft: 18,
              wordBreak: "break-word",
            }}
          >
            {pin.address}
          </div>
        )}
      </div>

      {/* 하단: 날짜 + written by — PinPopup 의 bottom row 그대로. */}
      <div
        style={{
          marginTop: 12,
          paddingTop: 10,
          borderTop: `1px solid ${colors.hairline}`,
          fontFamily: fonts.mono,
          fontSize: 12,
          color: colors.inkSoft,
          fontStyle: "italic",
          marginBottom: 14,
        }}
      >
        {dateLabel}&nbsp;&nbsp;
        <span style={{ whiteSpace: "nowrap" }}>
          <span
            style={{
              fontFamily: fonts.sans,
              fontStyle: "italic",
              color: colors.inkSoft,
              fontWeight: 400,
              fontSize: 11,
              marginRight: 6,
            }}
          >
            written by
          </span>
          <span
            style={{
              fontFamily: fonts.sans,
              fontStyle: "normal",
              color: colors.ink,
              fontWeight: 600,
            }}
          >
            {authorLabel}
          </span>
        </span>
      </div>

      {/* 버튼 */}
      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub
          onClick={onSkip}
          style={{ flex: 1, padding: "10px 0", fontSize: 13 }}
        >
          나중에요
        </BtnSub>
        <BtnPrimary
          onClick={onConfirm}
          style={{ flex: 1, padding: "10px 0", fontSize: 13 }}
        >
          네, 다녀왔어요 →
        </BtnPrimary>
      </div>
    </div>
  );
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return "";
  try {
    const d = new Date(iso);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${y}.${m}.${day}`;
  } catch {
    return "";
  }
}
