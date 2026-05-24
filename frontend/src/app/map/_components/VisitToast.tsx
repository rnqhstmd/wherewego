"use client";

import type { PinSummaryResponse } from "@/lib/api/types";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { colors, fonts } from "@/lib/design/tokens";
import { useMediaQuery } from "@/lib/hooks/useMediaQuery";

interface VisitToastProps {
  pin: PinSummaryResponse;
  onSkip: () => void;
  onConfirm: () => void;
}

/**
 * Phase 10 — 장소 방문 감지 토스트 (설계 §5.4).
 *
 * 위치:
 *  - 모바일 (max-width 767px): bottom 100 (ActionBar 위), left/right 12.
 *  - 데스크탑: bottom 32, left 76 (DesktopActionPill 우측), max-width 360.
 *
 * mount 시 슬라이드 업 200ms ease-out. 자동 닫힘 없음 — dismiss 는 부모(MapClient) 가 제어.
 * `role="status"` 로 스크린리더에 변경을 알린다.
 */
export default function VisitToast({ pin, onSkip, onConfirm }: VisitToastProps) {
  // 매체 쿼리: 기존 컨벤션의 useMediaQuery 훅 사용 (SSR/하이드레이션 안전).
  // 데스크탑은 useMediaQuery("(min-width: 768px)") = true. 모바일은 false.
  const isDesktop = useMediaQuery("(min-width: 768px)");
  const isMobile = !isDesktop;

  const containerStyle: React.CSSProperties = isMobile
    ? {
        position: "fixed",
        bottom: 100,
        left: 12,
        right: 12,
        zIndex: 25,
      }
    : {
        position: "fixed",
        bottom: 32,
        left: 76,
        maxWidth: 360,
        zIndex: 25,
      };

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        ...containerStyle,
        background: colors.panel,
        borderRadius: 14,
        padding: "14px 16px",
        boxShadow: `0 8px 24px ${colors.shadowMd}`,
        border: `1px solid ${colors.hairline}`,
        fontFamily: fonts.sans,
        animation: "maygo-visit-toast-slide-up 200ms ease-out both",
      }}
    >
      <div
        style={{
          fontSize: 14,
          fontWeight: 600,
          color: colors.ink,
          marginBottom: pin.address ? 4 : 12,
          wordBreak: "break-word",
        }}
      >
        📍 {pin.placeName} 근처에 계신가요?
      </div>
      {pin.address && (
        <div
          style={{
            fontSize: 12,
            color: colors.inkSoft,
            marginBottom: 12,
            wordBreak: "break-word",
          }}
        >
          {pin.address}
        </div>
      )}
      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub
          onClick={onSkip}
          style={{ flex: 1, padding: "10px 0", fontSize: 13 }}
        >
          다음에 올게요
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
