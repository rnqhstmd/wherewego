"use client";

import { useEffect, useRef, useState } from "react";
import type mapboxgl from "mapbox-gl";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";
import { SpeechBubblePopup } from "@/components/ui/SpeechBubblePopup";
import { PinTag as PinTagChip } from "@/components/ui/PinTag";
import type { PinDotType } from "@/components/ui/PinDot";
import { colors, fonts } from "@/lib/design/tokens";
import PinPopupMemoEditor from "./PinPopupMemoEditor";

interface PinPopupProps {
  pin: PinSummaryResponse;
  map: mapboxgl.Map | null;
  authorLabel?: string;
  onTagChange: (
    pinId: number,
    nextTag: PinTag,
  ) => Promise<{ ok: boolean; message?: string }>;
  onMemoChange: (
    pinId: number,
    nextMemo: string,
  ) => Promise<{ ok: boolean; message?: string }>;
  onPlaceNameChange: (
    pinId: number,
    nextPlaceName: string,
  ) => Promise<{ ok: boolean; message?: string }>;
  onRequestDelete: (pin: PinSummaryResponse) => void;
  deleteError: string | null;
  onRequestCoordinateEdit: (pin: PinSummaryResponse) => void;
  coordinateError: string | null;
}

type PopupMode = "view" | "menu" | "edit";
type EditTab = "place" | "tag" | "memo";

export default function PinPopup({
  pin,
  map,
  authorLabel,
  onTagChange,
  onMemoChange,
  onPlaceNameChange,
  onRequestDelete,
  deleteError,
  onRequestCoordinateEdit,
  coordinateError,
}: PinPopupProps) {
  const [screenPos, setScreenPos] = useState<{ x: number; y: number } | null>(
    null,
  );
  const [mode, setMode] = useState<PopupMode>("view");
  const [editTab, setEditTab] = useState<EditTab>("place");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [memoPending, setMemoPending] = useState(false);
  const [memoError, setMemoError] = useState<string | null>(null);
  const [placeDraft, setPlaceDraft] = useState(pin.placeName);
  const [placePending, setPlacePending] = useState(false);
  const [placeError, setPlaceError] = useState<string | null>(null);

  // mountedRef: setup 시 true로 reset (Strict Mode dev 이중 mount 안전).
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // 좌표 변경 실패 시 자동으로 edit 모드 펼침
  useEffect(() => {
    if (coordinateError) setMode("edit");
  }, [coordinateError]);

  // 다른 핀 선택 → 모드/에러 리셋 (렌더 중 분기 — React 권장 패턴)
  const [trackedPinId, setTrackedPinId] = useState<number>(pin.id);
  if (trackedPinId !== pin.id) {
    setTrackedPinId(pin.id);
    setMode("view");
    setEditTab("place");
    setError(null);
    setMemoPending(false);
    setMemoError(null);
    setPlaceDraft(pin.placeName);
    setPlacePending(false);
    setPlaceError(null);
  }

  useEffect(() => {
    if (!map) return;
    const lng = Number(pin.longitude);
    const lat = Number(pin.latitude);
    const updatePos = () => {
      const p = map.project([lng, lat]);
      setScreenPos({ x: p.x, y: p.y });
    };
    updatePos();
    map.on("move", updatePos);
    map.on("zoom", updatePos);
    return () => {
      map.off("move", updatePos);
      map.off("zoom", updatePos);
    };
  }, [map, pin.longitude, pin.latitude]);

  if (!screenPos) return null;

  const formattedDate = new Date(pin.createdAt)
    .toLocaleDateString("ko-KR", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    })
    .replace(/\s/g, "")
    .replace(/\.$/, "");

  const handleTagToggle = async (nextTag: PinTag) => {
    if (nextTag === pin.tag || pending) return;
    setPending(true);
    setError(null);
    const result = await onTagChange(pin.id, nextTag);
    if (!mountedRef.current) return;
    setPending(false);
    if (!result.ok) {
      setError(result.message ?? "태그 변경에 실패했어요");
    } else {
      setMode("view");
    }
  };

  const handleSavePlaceName = async () => {
    const trimmed = placeDraft.trim();
    if (!trimmed || trimmed === pin.placeName || placePending) return;
    setPlacePending(true);
    setPlaceError(null);
    const result = await onPlaceNameChange(pin.id, trimmed);
    if (!mountedRef.current) return;
    setPlacePending(false);
    if (result.ok) {
      setMode("view");
    } else {
      setPlaceError(result.message ?? "장소 이름 저장에 실패했어요");
    }
  };

  const handleSaveMemo = async (nextMemo: string) => {
    if (memoPending) return;
    setMemoPending(true);
    setMemoError(null);
    const result = await onMemoChange(pin.id, nextMemo);
    if (!mountedRef.current) return;
    setMemoPending(false);
    if (result.ok) {
      setMode("view");
    } else {
      setMemoError(result.message ?? "메모 저장에 실패했어요");
    }
  };

  const handleMenuClick = () => {
    setMode((prev) =>
      prev === "menu" ? "view" : prev === "edit" ? "view" : "menu",
    );
  };

  // ─── 메뉴 popover (수정 / 삭제) ────────────────────────────────
  // menu mode는 popup footer가 아닌 별도 dialog로 노출.

  // ─── 수정 모드 (탭 + 폼) ─────────────────────────────────────
  const tabHeader = (
    <div
      style={{
        display: "flex",
        gap: 0,
        borderBottom: `1px solid ${colors.hairline}`,
        marginBottom: 12,
      }}
      role="tablist"
    >
      {renderTabButton("place", "장소", editTab, setEditTab)}
      {renderTabButton("tag", "태그", editTab, setEditTab)}
      {renderTabButton("memo", "메모", editTab, setEditTab)}
    </div>
  );

  const placePanel = (
    <div>
      <input
        type="text"
        value={placeDraft}
        onChange={(e) => setPlaceDraft(e.target.value)}
        disabled={placePending}
        maxLength={100}
        placeholder="장소 이름"
        style={{
          width: "100%",
          padding: "8px 10px",
          fontFamily: fonts.sans,
          fontSize: 13,
          border: `1px solid ${colors.hairline}`,
          borderRadius: 8,
          outline: "none",
          color: colors.ink,
          background: "#fff",
        }}
      />
      <div
        style={{
          display: "flex",
          justifyContent: "flex-end",
          gap: 6,
          marginTop: 8,
        }}
      >
        <button
          type="button"
          onClick={() => {
            setPlaceDraft(pin.placeName);
            setPlaceError(null);
            setMode("view");
          }}
          disabled={placePending}
          style={linkButtonStyle(colors.inkSoft)}
        >
          취소
        </button>
        <button
          type="button"
          onClick={handleSavePlaceName}
          disabled={
            placePending ||
            !placeDraft.trim() ||
            placeDraft.trim() === pin.placeName
          }
          style={{
            ...linkButtonStyle(colors.cta),
            fontWeight: 700,
          }}
        >
          {placePending ? "저장 중..." : "저장"}
        </button>
      </div>
      {placeError && (
        <div style={{ ...inlineErrorStyle, marginTop: 6 }}>{placeError}</div>
      )}
    </div>
  );

  const tagPanel = (
    <div>
      <div style={{ display: "flex", gap: 8, marginBottom: error ? 8 : 0, flexWrap: "wrap" }}>
        <PinTagChip
          type="MEMORY"
          active={pin.tag === "MEMORY"}
          disabled={pending}
          onClick={() => handleTagToggle("MEMORY")}
        />
        <PinTagChip
          type="WISH"
          active={pin.tag === "WISH"}
          disabled={pending}
          onClick={() => handleTagToggle("WISH")}
        />
        <PinTagChip
          type="REEL"
          active={pin.tag === "REEL"}
          disabled={pending}
          onClick={() => handleTagToggle("REEL")}
        />
        {pending && (
          <span style={hintTextStyle}>저장 중...</span>
        )}
      </div>
      {error && <div style={inlineErrorStyle}>{error}</div>}
    </div>
  );

  const memoPanel = (
    <PinPopupMemoEditor
      key={pin.id}
      initialMemo={pin.memo}
      pending={memoPending}
      error={memoError}
      onSave={handleSaveMemo}
      onCancel={() => setMode("view")}
    />
  );

  const editFooter = (
    <div>
      {tabHeader}
      {editTab === "place"
        ? placePanel
        : editTab === "tag"
          ? tagPanel
          : memoPanel}
      {/* 보조 액션: 좌표 수정 / 취소 (메모 탭의 저장/취소는 PinPopupMemoEditor 자체에 있음) */}
      <div
        style={{
          marginTop: 14,
          paddingTop: 10,
          borderTop: `1px solid ${colors.hairline}`,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <button
          type="button"
          onClick={() => onRequestCoordinateEdit(pin)}
          style={linkButtonStyle(colors.inkSoft)}
        >
          좌표 수정
        </button>
        <button
          type="button"
          onClick={() => setMode("view")}
          style={linkButtonStyle(colors.inkSoft)}
        >
          닫기
        </button>
      </div>
      {coordinateError && (
        <div style={{ ...inlineErrorStyle, marginTop: 6 }}>
          {coordinateError}
        </div>
      )}
    </div>
  );

  const footer = mode === "edit" ? editFooter : null;

  // ⋮ 바로 아래에 뜨는 드롭다운형 popover (backdrop 없음, popup 내부에 absolute).
  const menuPopover =
    mode === "menu" ? (
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          position: "absolute",
          top: 50,
          right: 14,
          minWidth: 120,
          background: colors.panel,
          borderRadius: 10,
          boxShadow: `0 8px 20px ${colors.shadowMd}, 0 0 0 1px ${colors.hairline}`,
          padding: 4,
          display: "flex",
          flexDirection: "column",
          gap: 2,
          zIndex: 5,
        }}
      >
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            setMode("edit");
            // 수정 진입 시 항상 장소 탭을 먼저 노출 (사용자 UX 결정).
            setEditTab("place");
          }}
          style={dropdownItemStyle()}
        >
          수정
        </button>
        <button
          type="button"
          onMouseDown={(e) => {
            // popover 자체에 onClick(e.stopPropagation) 핸들러가 있어 button onClick까지
            // 도달 못 하는 케이스가 있어 mouseDown으로 트리거. setMode 변경은 하지 않는다
            // — dialog 모달 useEffect와 같은 cycle에서 충돌하면 모달이 안 뜬다.
            e.stopPropagation();
            e.preventDefault();
            onRequestDelete(pin);
          }}
          style={dropdownItemStyle(colors.pinNew)}
        >
          삭제
        </button>
        {deleteError && (
          <div style={{ ...inlineErrorStyle, marginTop: 2, padding: "4px 8px" }}>
            {deleteError}
          </div>
        )}
      </div>
    ) : null;

  return (
    <>
      <SpeechBubblePopup
        pinX={screenPos.x}
        pinY={screenPos.y}
        memo={pin.memo ?? ""}
        place={pin.placeName}
        addr={pin.address ?? ""}
        author={authorLabel ?? String(pin.createdBy)}
        date={formattedDate}
        pinType={
          (pin.tag === "MEMORY"
            ? "memory"
            : pin.tag === "REEL"
              ? "reel"
              : "wish") satisfies PinDotType
        }
        instagramUrl={pin.instagramUrl}
        collapseBody={mode === "edit"}
        onMenuClick={handleMenuClick}
        footerContent={footer}
      >
        {menuPopover}
      </SpeechBubblePopup>
    </>
  );
}

// ─── 스타일 헬퍼 ───────────────────────────────────────────────
function dropdownItemStyle(color: string = colors.ink) {
  return {
    padding: "8px 12px",
    border: "none",
    background: "transparent",
    color,
    fontFamily: fonts.sans,
    fontSize: 13,
    fontWeight: 600,
    textAlign: "left" as const,
    cursor: "pointer",
    borderRadius: 6,
  };
}

function linkButtonStyle(color: string) {
  return {
    padding: "4px 6px",
    border: "none",
    background: "transparent",
    color,
    fontFamily: fonts.sans,
    fontSize: 12,
    fontWeight: 600,
    cursor: "pointer",
  };
}

const inlineErrorStyle = {
  fontFamily: fonts.sans,
  fontSize: 12,
  color: colors.pinNew,
  marginTop: 6,
} as const;

const hintTextStyle = {
  fontFamily: fonts.sans,
  fontSize: 12,
  color: colors.inkSoft,
  alignSelf: "center" as const,
};

function renderTabButton(
  target: EditTab,
  label: string,
  current: EditTab,
  onSelect: (t: EditTab) => void,
) {
  const active = current === target;
  return (
    <button
      key={target}
      type="button"
      role="tab"
      aria-selected={active}
      onClick={() => onSelect(target)}
      style={{
        padding: "8px 14px",
        border: "none",
        background: "transparent",
        color: active ? colors.ink : colors.inkSoft,
        fontFamily: fonts.sans,
        fontSize: 13,
        fontWeight: active ? 700 : 500,
        cursor: "pointer",
        borderBottom: active
          ? `2px solid ${colors.ink}`
          : "2px solid transparent",
        marginBottom: -1,
      }}
    >
      {label}
    </button>
  );
}
