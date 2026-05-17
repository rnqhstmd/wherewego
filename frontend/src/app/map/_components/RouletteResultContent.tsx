"use client";

import { colors, fonts } from "@/lib/design/tokens";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { PinDot } from "@/components/ui/PinDot";
import { IconShuffle, IconLocation } from "@/components/icons";
import type { PinSummaryResponse } from "@/lib/api/types";

interface RouletteResultContentProps {
  pin: PinSummaryResponse;
  distanceKm: number;
  onShowOnMap: () => void;
  onReRoll: () => void;
}

/**
 * M-6c 결과 카드 (설계 §10, MUST-2).
 *
 * 상단에 거리 강조 헤더("여기서 N km", rust 컬러 + IconLocation)를 노출하여
 * 1/5/10km 어느 범위에서 뽑혔는지를 거리 숫자로 자연스럽게 표현한다.
 *
 * 1km 미만은 m 단위로 표시 (예: "여기서 800m").
 */
export default function RouletteResultContent({
  pin,
  distanceKm,
  onShowOnMap,
  onReRoll,
}: RouletteResultContentProps) {
  const distanceLabel =
    distanceKm < 1
      ? `${Math.round(distanceKm * 1000)}m`
      : `${distanceKm.toFixed(1)}km`;
  const isMemory = pin.tag === "MEMORY";

  return (
    <div>
      {/* MUST-2 거리 강조 헤더 */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 6,
          marginBottom: 14,
        }}
      >
        <IconLocation size={16} color={colors.cta} />
        <span
          style={{
            fontFamily: fonts.sans,
            fontSize: 13.5,
            fontWeight: 600,
            color: colors.cta,
          }}
        >
          여기서 {distanceLabel}
        </span>
      </div>

      <div
        style={{
          background: colors.bg,
          borderRadius: 12,
          border: `1px solid ${colors.hairline}`,
          padding: "14px 16px",
          marginBottom: 14,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            marginBottom: 6,
          }}
        >
          <PinDot
            type={isMemory ? "memory" : "place"}
            size={isMemory ? 11 : 9}
          />
          <span
            style={{
              fontFamily: fonts.serif,
              fontSize: 16,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            {pin.placeName}
          </span>
        </div>
        {pin.address ? (
          <div
            style={{
              fontFamily: fonts.mono,
              fontSize: 11.5,
              color: colors.inkSoft,
              marginBottom: pin.memo ? 8 : 0,
            }}
          >
            {pin.address}
          </div>
        ) : null}
        {pin.memo ? (
          <div
            style={{
              fontFamily: fonts.sans,
              fontSize: 14,
              color: colors.ink,
              lineHeight: 1.5,
            }}
          >
            {`"${pin.memo}"`}
          </div>
        ) : null}
      </div>

      <div style={{ display: "flex", gap: 8 }}>
        <BtnPrimary
          onClick={onShowOnMap}
          style={{ flex: 1.6, padding: "12px 0" }}
        >
          지도에서 보기
        </BtnPrimary>
        <BtnSub
          onClick={onReRoll}
          style={{
            flex: 1,
            padding: "12px 0",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            gap: 6,
          }}
        >
          <IconShuffle size={14} color={colors.ctaSub} />
          <span>다시</span>
        </BtnSub>
      </div>
    </div>
  );
}
