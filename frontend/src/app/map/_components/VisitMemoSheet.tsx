"use client";

import { useState } from "react";
import type { PinSummaryResponse } from "@/lib/api/types";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { colors, fonts } from "@/lib/design/tokens";

interface VisitMemoSheetProps {
  pin: PinSummaryResponse;
  visitedAt: Date;
  /**
   * 메모 저장 콜백. 성공 시 시트는 호출처가 닫는다.
   * 실패 시 message 를 인라인 에러로 표시하고 시트는 유지된다 (FR-VD-22).
   */
  onSave: (memo: string) => Promise<{ ok: boolean; message?: string }>;
  /**
   * 건너뛰기 — 2차 PATCH 미발사 (FR-VD-20). 호출처가 시트를 닫는다.
   */
  onSkip: () => void;
}

/**
 * Phase 10 — VISIT_DETECTED 후속 메모 입력 시트 (설계 §5.5).
 *
 * 본 컴포넌트는 패널 컨테이너(Sheet/SidePanel)를 직접 렌더하지 않는다.
 * MapClient 의 `renderPanel("...", <VisitMemoSheet .../>)` 로 감싸 사용한다
 * (PinCoordinateEditPicker 와 동일 패턴).
 *
 * 동작:
 *  - 상단 ✓ 장소명 + 방문 날짜 (YYYY년 M월 D일).
 *  - textarea maxLength 500.
 *  - 저장 중: BtnPrimary disabled + "저장 중...".
 *  - 저장 실패: 인라인 에러 노출, 시트 유지, 입력값 보존.
 *  - 건너뛰기: 2차 PATCH 미발사 — onSkip 만 호출.
 */
export default function VisitMemoSheet({
  pin,
  visitedAt,
  onSave,
  onSkip,
}: VisitMemoSheetProps) {
  const [memo, setMemo] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      const result = await onSave(memo);
      if (!result.ok) {
        setError(result.message ?? "메모 저장에 실패했어요");
      }
      // 성공 시 호출처가 시트를 닫으므로 별도 cleanup 불필요.
    } finally {
      setSaving(false);
    }
  };

  const pad = (n: number) => String(n).padStart(2, "0");
  const dateLabel = `다녀온 날 · ${visitedAt.getFullYear()}.${pad(visitedAt.getMonth() + 1)}.${pad(visitedAt.getDate())}`;

  return (
    <div>
      <div
        style={{
          fontFamily: fonts.serif,
          fontSize: 16,
          fontWeight: 700,
          color: colors.ink,
          marginBottom: 4,
          wordBreak: "break-word",
        }}
      >
        🌸 {pin.placeName}, 다녀온 흔적을 남겨볼까요?
      </div>
      <div
        style={{
          fontFamily: fonts.mono,
          fontSize: 12,
          color: colors.inkSoft,
          marginBottom: 14,
        }}
      >
        {dateLabel}
      </div>

      <textarea
        value={memo}
        onChange={(e) => setMemo(e.target.value)}
        placeholder="오늘의 순간을 짧게 남겨두세요 (선택)"
        maxLength={500}
        disabled={saving}
        style={{
          width: "100%",
          boxSizing: "border-box",
          border: `1.5px solid ${colors.hairline}`,
          borderRadius: 10,
          padding: "12px 14px",
          minHeight: 96,
          background: colors.bg,
          fontFamily: fonts.sans,
          fontSize: 14,
          color: colors.ink,
          marginBottom: 12,
          resize: "none",
          outline: "none",
        }}
      />

      {error && (
        <div
          role="alert"
          style={{
            padding: "10px 12px",
            marginBottom: 12,
            background: `${colors.pinNew}15`,
            color: colors.pinNew,
            borderRadius: 8,
            fontSize: 13,
            fontFamily: fonts.sans,
          }}
        >
          {error}
        </div>
      )}

      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub
          onClick={onSkip}
          disabled={saving}
          style={{ flex: 1, padding: "11px 0" }}
        >
          건너뛰기
        </BtnSub>
        <BtnPrimary
          onClick={handleSave}
          disabled={saving}
          style={{ flex: 1, padding: "11px 0" }}
        >
          {saving ? "저장 중..." : "저장"}
        </BtnPrimary>
      </div>
    </div>
  );
}
