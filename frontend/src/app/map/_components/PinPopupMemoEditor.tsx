"use client";

import { useState } from "react";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { colors, fonts } from "@/lib/design/tokens";
import { MEMO_MAX_LENGTH } from "@/lib/pin/constants";

interface PinPopupMemoEditorProps {
  initialMemo: string | null;
  pending: boolean;
  error: string | null;
  onSave: (nextMemo: string) => void;
  onCancel: () => void;
}

/**
 * SpeechBubblePopup ⋮ 펼침 영역에서 메모를 인라인 편집하는 컴포넌트 (FR-MMO-2).
 *
 * - 내부 `memo` state 한 개. `error` prop 변경 시 reset 하지 않아 저장 실패 시
 *   입력값이 보존된다 (401 텍스트 보존 회귀 방지).
 * - 빈 문자열도 그대로 onSave 로 전달하여 잠금 해제(BR-3)를 지원한다.
 * - 카운터 색상: 정상 `colors.inkSoft`, 450자 이상 `colors.cta`,
 *   501자 이상 `colors.pinNew`.
 * - 디자인 토큰 + inline style 만 사용 (Tailwind 미사용).
 */
export default function PinPopupMemoEditor({
  initialMemo,
  pending,
  error,
  onSave,
  onCancel,
}: PinPopupMemoEditorProps) {
  const [memo, setMemo] = useState<string>(initialMemo ?? "");

  const memoLength = memo.length;
  const isMemoTooLong = memoLength > MEMO_MAX_LENGTH;
  const changed = memo !== (initialMemo ?? "");
  const canSave = changed && !isMemoTooLong && !pending;

  const counterColor = isMemoTooLong
    ? colors.pinNew
    : memoLength >= MEMO_MAX_LENGTH - 50
      ? colors.cta
      : colors.inkSoft;

  return (
    <div>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          marginBottom: 6,
        }}
      >
        <span
          style={{
            fontFamily: fonts.sans,
            fontSize: 13,
            fontWeight: 600,
            color: colors.ink,
          }}
        >
          메모 편집
        </span>
        <span
          style={{
            fontFamily: fonts.sans,
            fontSize: 11,
            color: counterColor,
          }}
        >
          {memoLength}/{MEMO_MAX_LENGTH}
        </span>
      </div>

      <textarea
        value={memo}
        onChange={(event) => setMemo(event.target.value)}
        maxLength={MEMO_MAX_LENGTH}
        placeholder="비워두면 챗봇 자동 메모를 다시 받아요"
        autoFocus
        style={{
          width: "100%",
          boxSizing: "border-box",
          minHeight: 80,
          padding: "10px 12px",
          background: colors.bg,
          border: `1.5px solid ${colors.hairline}`,
          borderRadius: 8,
          fontFamily: fonts.sans,
          fontSize: 13,
          color: colors.ink,
          resize: "none",
          outline: "none",
        }}
      />

      {isMemoTooLong && (
        <div
          style={{
            marginTop: 6,
            fontFamily: fonts.sans,
            fontSize: 12,
            color: colors.pinNew,
          }}
        >
          {`메모는 최대 ${MEMO_MAX_LENGTH}자까지 입력할 수 있어요`}
        </div>
      )}

      {error && (
        <div
          style={{
            marginTop: 8,
            padding: "8px 10px",
            background: `${colors.pinNew}15`,
            color: colors.pinNew,
            borderRadius: 8,
            fontFamily: fonts.sans,
            fontSize: 12,
          }}
        >
          {error}
        </div>
      )}

      <div
        style={{
          display: "flex",
          justifyContent: "flex-end",
          gap: 8,
          marginTop: 10,
        }}
      >
        <BtnSub
          onClick={onCancel}
          disabled={pending}
          style={{ padding: "8px 14px", fontSize: 13 }}
        >
          취소
        </BtnSub>
        <BtnPrimary
          onClick={() => onSave(memo)}
          disabled={!canSave}
          style={{ padding: "8px 14px", fontSize: 13 }}
        >
          {pending ? "저장 중..." : "저장"}
        </BtnPrimary>
      </div>
    </div>
  );
}
