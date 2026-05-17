"use client";

import { colors, fonts } from "@/lib/design/tokens";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { IconPlus } from "@/components/icons";

interface EmptyMapCardProps {
  isDesktop: boolean;
  onAddPin: () => void;
}

/**
 * 핀이 0개일 때 지도 중앙에 표시되는 빈 상태 안내 카드 (FR-SYS-4, AC-17).
 *
 * 모바일/데스크탑 반응형 분기:
 *  - 데스크탑: "아직 핀이 없어요" + 좌측 + 버튼 안내 + CTA 버튼
 *  - 모바일: "첫 핀을 찍어볼까요?" + 아래 + 버튼 안내 (CTA 없음, ActionBar 이용)
 *
 * `screens-basic.jsx::EmptyMapMobile/Desktop` 의 통합 변환.
 */
export default function EmptyMapCard({ isDesktop, onAddPin }: EmptyMapCardProps) {
  return (
    <div
      style={{
        position: "absolute",
        top: "50%",
        left: "50%",
        transform: "translate(-50%, -50%)",
        background: colors.panel,
        borderRadius: 18,
        padding: "24px 28px",
        boxShadow: `0 8px 24px ${colors.shadowMd}`,
        border: `1px solid ${colors.hairline}`,
        textAlign: "center",
        maxWidth: 320,
        zIndex: 10,
      }}
    >
      <div
        style={{
          width: 56,
          height: 56,
          borderRadius: "50%",
          background: `${colors.cta}15`,
          margin: "0 auto 16px",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <IconPlus size={28} color={colors.cta} />
      </div>
      <div
        style={{
          fontFamily: fonts.emo,
          fontSize: 18,
          fontWeight: 700,
          color: colors.ink,
          marginBottom: 8,
        }}
      >
        {isDesktop ? "아직 핀이 없어요" : "첫 핀을 찍어볼까요?"}
      </div>
      <div
        style={{
          fontFamily: fonts.sans,
          fontSize: 13.5,
          color: colors.inkSoft,
          lineHeight: 1.6,
          marginBottom: 18,
          whiteSpace: "pre-line",
        }}
      >
        {isDesktop
          ? "지도를 클릭하거나 왼쪽 + 버튼을 눌러보세요"
          : "지도를 이동해 위치를 정하고\n아래 + 버튼을 눌러보세요"}
      </div>
      {isDesktop && (
        <BtnPrimary onClick={onAddPin} style={{ padding: "12px 24px" }}>
          ＋ 첫 핀 추가하기
        </BtnPrimary>
      )}
    </div>
  );
}
