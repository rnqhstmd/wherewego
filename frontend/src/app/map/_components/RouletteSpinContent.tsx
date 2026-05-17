"use client";

import { colors, fonts } from "@/lib/design/tokens";
import { PinDot } from "@/components/ui/PinDot";

interface RouletteSpinContentProps {
  radiusKm: number;
  candidateCount: number;
}

/**
 * M-6 추첨중 시트 콘텐츠 (설계 §10).
 *
 * 디자인 번들 screens-mobile.jsx::M-6의 추첨 중 표현을 컴포넌트로 분리.
 * 핀 도트 + 회전 스피너 + "추첨중..." 텍스트 + 메타 정보(범위·후보 수).
 */
export default function RouletteSpinContent({
  radiusKm,
  candidateCount,
}: RouletteSpinContentProps) {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        padding: "4px 0 8px",
      }}
    >
      <div
        style={{
          display: "flex",
          gap: 14,
          marginBottom: 14,
          alignItems: "center",
        }}
      >
        <PinDot type="place" size={14} style={{ opacity: 0.4 }} />
        <PinDot type="memory" size={16} />
        <div
          style={{
            width: 22,
            height: 22,
            borderRadius: "50%",
            border: `2.5px solid ${colors.cta}`,
            borderTopColor: "transparent",
            animation: "roulette-spin 1s linear infinite",
          }}
        />
        <PinDot type="place" size={14} />
        <PinDot type="memory" size={12} style={{ opacity: 0.4 }} />
      </div>
      <div
        style={{
          fontFamily: fonts.serif,
          fontSize: 18,
          fontWeight: 700,
          color: colors.ink,
          marginBottom: 4,
        }}
      >
        추첨중...
      </div>
      <div
        style={{
          fontFamily: fonts.mono,
          fontSize: 12,
          color: colors.inkSoft,
        }}
      >
        {radiusKm}km 이내 · 장소 핀 {candidateCount}개 중에서
      </div>
      <style>{`@keyframes roulette-spin { to { transform: rotate(360deg) } }`}</style>
    </div>
  );
}
