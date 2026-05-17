"use client";

import { useState, useTransition } from "react";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { PinTag } from "@/components/ui/PinTag";
import { PanelLabel } from "@/components/ui/PanelLabel";
import { HLine } from "@/components/ui/HLine";
import { Input } from "@/components/ui/Input";
import { colors, fonts } from "@/lib/design/tokens";
import type { PinSummaryResponse, PinTag as PinTagType } from "@/lib/api/types";
import { createPinAction } from "../actions";
import type { NewPinOrigin } from "./types";

interface MemoTagPanelContentProps {
  origin: NewPinOrigin;
  groupId: number;
  onCancel: () => void;
  onSuccess: (pin: PinSummaryResponse) => void;
}

/**
 * 메모/태그 입력 + 저장 패널.
 *
 * - origin.editable === true (Crosshair 진입) 인 경우 장소 이름 입력 필드 노출.
 * - origin.editable === false (검색 진입) 인 경우 placeName 고정, 표시만.
 * - 태그 필수, 메모는 선택. ApiError 코드별 한국어 메시지 매핑.
 */
export default function MemoTagPanelContent({
  origin,
  groupId,
  onCancel,
  onSuccess,
}: MemoTagPanelContentProps) {
  const [tag, setTag] = useState<PinTagType | null>(null);
  const [memo, setMemo] = useState("");
  const [placeName, setPlaceName] = useState(origin.placeName);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const effectivePlaceName = origin.editable ? placeName.trim() : origin.placeName;
  const canSubmit = !!tag && effectivePlaceName.length > 0 && !pending;

  const handleSave = () => {
    if (!tag) return;
    if (origin.editable && placeName.trim().length === 0) {
      setError("장소 이름을 입력해주세요");
      return;
    }
    startTransition(async () => {
      const result = await createPinAction(groupId, {
        placeName: effectivePlaceName,
        address: origin.address,
        latitude: origin.latitude,
        longitude: origin.longitude,
        memo: memo.trim() || null,
        tag,
      });
      if (result.ok) {
        onSuccess(result.data);
      } else if (result.code === "PLC_DUPLICATE_PIN") {
        setError("이미 등록된 장소예요");
      } else if (result.code === "GROUP_NOT_MEMBER") {
        setError("그룹의 활성 멤버만 핀을 추가할 수 있어요");
      } else if (result.code === "PIN_PLACE_NAME_INVALID") {
        setError("장소 이름은 1~200자여야 합니다");
      } else if (result.code === "PIN_MEMO_TOO_LONG") {
        setError("메모는 500자까지 입력할 수 있어요");
      } else {
        setError(result.message);
      }
    });
  };

  return (
    <div>
      <div
        style={{
          fontFamily: fonts.sans,
          fontSize: 16,
          fontWeight: 700,
          color: colors.ink,
          marginBottom: 6,
        }}
      >
        새 핀 추가
      </div>
      <div
        style={{
          fontFamily: fonts.mono,
          fontSize: 12,
          color: colors.inkSoft,
          marginBottom: 16,
        }}
      >
        📍 {origin.editable
          ? `${origin.latitude.toFixed(6)}, ${origin.longitude.toFixed(6)}`
          : `${origin.placeName}${origin.address ? ` · ${origin.address}` : ""}`}
      </div>
      <HLine style={{ marginBottom: 14 }} />

      {origin.editable && (
        <>
          <PanelLabel>장소 이름</PanelLabel>
          <Input
            placeholder="예: 우리집"
            value={placeName}
            onChange={setPlaceName}
            style={{ marginBottom: 16 }}
          />
        </>
      )}

      <PanelLabel>태그</PanelLabel>
      <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        <PinTag
          type="place"
          active={tag === "PLACE"}
          onClick={() => setTag("PLACE")}
        />
        <PinTag
          type="memory"
          active={tag === "MEMORY"}
          onClick={() => setTag("MEMORY")}
        />
      </div>

      <PanelLabel>메모 (선택)</PanelLabel>
      <textarea
        value={memo}
        onChange={(e) => setMemo(e.target.value)}
        placeholder="메모를 입력해 보세요..."
        maxLength={500}
        style={{
          width: "100%",
          boxSizing: "border-box",
          border: `1.5px solid ${colors.hairline}`,
          borderRadius: 10,
          padding: "12px 14px",
          minHeight: 72,
          background: colors.bg,
          fontFamily: fonts.sans,
          fontSize: 14,
          color: colors.ink,
          marginBottom: 16,
          resize: "vertical",
          outline: "none",
        }}
      />

      {error && (
        <div
          style={{
            padding: "10px 12px",
            marginBottom: 12,
            background: `${colors.pinNew}15`,
            color: colors.pinNew,
            borderRadius: 8,
            fontSize: 13,
          }}
        >
          {error}
        </div>
      )}

      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub
          onClick={onCancel}
          style={{ flex: 1, padding: "11px 0" }}
          disabled={pending}
        >
          취소
        </BtnSub>
        <BtnPrimary
          onClick={handleSave}
          disabled={!canSubmit}
          style={{ flex: 1, padding: "11px 0" }}
        >
          {pending ? "저장 중..." : "저장"}
        </BtnPrimary>
      </div>
    </div>
  );
}
