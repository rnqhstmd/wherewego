"use client";

import { useEffect, useRef, useState } from "react";
import type mapboxgl from "mapbox-gl";
import type {
  PinSummaryResponse,
  PinTag,
  WantToggleResponse,
} from "@/lib/api/types";
import { SpeechBubblePopup } from "@/components/ui/SpeechBubblePopup";
import { PinTag as PinTagChip } from "@/components/ui/PinTag";
import type { PinDotType } from "@/components/ui/PinDot";
import { IconShare } from "@/components/icons";
import { colors, fonts } from "@/lib/design/tokens";
import PinPopupMemoEditor from "./PinPopupMemoEditor";
import PinShareSheet from "./PinShareSheet";
import TagProgressModal from "./TagProgressModal";

interface PinPopupProps {
  pin: PinSummaryResponse;
  map: mapboxgl.Map | null;
  authorLabel?: string;
  mapboxToken: string;
  mapboxStyleUrl: string | null;
  /** 카드 배경 지도에 함께 표시할 그룹 내 다른 핀들 (선택, PinShareSheet로 전달). */
  groupPins?: PinSummaryResponse[];
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
  /**
   * Phase 12 (FR-PIN-12-2): WANT(가고 싶어요) 토글 콜백. 부모(MapClient)가
   * Server Action 호출 + useOptimistic 갱신을 책임진다. 응답의 `wishConverted: true`
   * 시 부모가 마커 펄스 trigger 등 1회성 효과를 발사한다.
   */
  onWantToggle?: (pinId: number) => Promise<{
    ok: boolean;
    data?: WantToggleResponse;
    message?: string;
  }>;
  /**
   * Phase 12 (FR-PIN-12-11): REEL → WISH 자동 전환 시 0.5초 동안 true.
   * 본 컴포넌트는 SpeechBubblePopup 내부 마커(글리프) 펄스 통합을 추후 배치에서
   * 적용하도록 prop 만 정의해둔다. 현 배치 범위에서는 단순 patrhrough.
   */
  pulse?: boolean;
}

type PopupMode = "view" | "menu" | "edit";
type EditTab = "place" | "tag" | "memo";

export default function PinPopup({
  pin,
  map,
  authorLabel,
  mapboxToken,
  mapboxStyleUrl,
  groupPins,
  onTagChange,
  onMemoChange,
  onPlaceNameChange,
  onRequestDelete,
  deleteError,
  onRequestCoordinateEdit,
  coordinateError,
  onWantToggle,
  // pulse: Phase 12 §9.2 펄스 효과. 본 배치에서는 SpeechBubblePopup 글리프 통합 미적용 —
  // 추후 배치에서 SpeechBubblePopup pinType 자리 PinDot에 pulse prop 으로 전달 예정.
  // 현 시점에서는 받기만 하여 호출부 (MapClient) 가 안정적으로 prop 을 전달할 수 있게 한다.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  pulse: _pulse,
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
  // Phase 9: 공유 카드 시트 표시 여부.
  const [shareOpen, setShareOpen] = useState(false);

  // Phase 12 (FR-PIN-12-2): WANT 토글 진행 중 + 직전 에러 메시지.
  // 토글은 부모(MapClient)가 useOptimistic 으로 pin.wantCount/myWant 를 즉시 갱신하므로
  // 본 컴포넌트에서는 별도 옵티미스틱 상태를 유지하지 않고 pending 만 표시한다.
  const [wantPending, setWantPending] = useState(false);
  const [wantError, setWantError] = useState<string | null>(null);

  // Phase 12 (FR-PIN-12-28): 태그 진행 다이어그램 모달 노출 여부.
  const [progressModalOpen, setProgressModalOpen] = useState(false);

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
    setShareOpen(false);
    setWantPending(false);
    setWantError(null);
    setProgressModalOpen(false);
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

  // Phase 10 후속: MEMORY + visitedAt 이면 "다녀온 날", 그 외 createdAt 폴백.
  // WISH/REEL 은 항상 visitedAt=null 이므로 createdAt 사용.
  const dateSource =
    pin.tag === "MEMORY" && pin.visitedAt ? pin.visitedAt : pin.createdAt;
  const formattedDate = new Date(dateSource)
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

  /**
   * Phase 12 (FR-PIN-12-2): WANT 토글.
   * 부모(MapClient)가 useOptimistic 으로 pin.wantCount/myWant 를 즉시 반영하고,
   * 실패 시 transition 종료 → 자동 롤백된다. 본 컴포넌트는 pending/error 만 관리.
   */
  const handleWantToggle = async () => {
    if (!onWantToggle || wantPending || pin.tag === "MEMORY") return;
    setWantPending(true);
    setWantError(null);
    const result = await onWantToggle(pin.id);
    if (!mountedRef.current) return;
    setWantPending(false);
    if (!result.ok) {
      setWantError(result.message ?? "처리에 실패했어요");
    }
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

  // Phase 12 (FR-PIN-12-2, 28): view 모드 footer — 출처 뱃지 + WANT 토글 + 태그 진행 다이어그램 트리거.
  // MEMORY 핀은 WANT 버튼 미노출, 출처 뱃지와 ? 아이콘은 항상 노출.
  const sourceBadgeLabel = pin.instagramUrl ? "📹" : "✏️";
  const sourceBadgeTitle = pin.instagramUrl
    ? "릴스에서 발견한 곳"
    : "직접 추가한 곳";
  const wantLabel = pin.myWant ? "❤️ 가고 싶음" : "🤍 가고 싶어요";

  const viewFooter = (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        gap: 8,
        flexWrap: "wrap",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
        <span
          aria-label={sourceBadgeTitle}
          title={sourceBadgeTitle}
          style={{
            fontFamily: fonts.sans,
            fontSize: 11,
            fontWeight: 600,
            color: colors.inkSoft,
            padding: "3px 8px",
            borderRadius: 999,
            background: colors.bg,
            border: `1px solid ${colors.hairline}`,
            display: "inline-flex",
            alignItems: "center",
            gap: 4,
            lineHeight: 1,
          }}
        >
          {sourceBadgeLabel}
        </span>
        <button
          type="button"
          onClick={() => setProgressModalOpen(true)}
          aria-label="태그 진행 안내"
          style={{
            width: 20,
            height: 20,
            borderRadius: "50%",
            border: `1px solid ${colors.hairline}`,
            background: "transparent",
            color: colors.inkSoft,
            cursor: "pointer",
            fontFamily: fonts.sans,
            fontSize: 11,
            fontWeight: 700,
            padding: 0,
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            lineHeight: 1,
          }}
        >
          ?
        </button>
      </div>
      {pin.tag !== "MEMORY" && onWantToggle && (
        <button
          type="button"
          onClick={handleWantToggle}
          disabled={wantPending}
          aria-pressed={pin.myWant}
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 6,
            padding: "6px 12px",
            borderRadius: 999,
            border: `1px solid ${pin.myWant ? colors.cta : colors.hairline}`,
            background: pin.myWant ? `${colors.cta}14` : colors.panel,
            color: pin.myWant ? colors.cta : colors.ink,
            fontFamily: fonts.sans,
            fontSize: 12,
            fontWeight: 600,
            cursor: wantPending ? "wait" : "pointer",
            opacity: wantPending ? 0.6 : 1,
          }}
        >
          <span>{wantLabel}</span>
          {pin.wantCount > 0 && (
            <span
              style={{
                fontFamily: fonts.mono,
                fontSize: 11,
                fontWeight: 700,
                color: pin.myWant ? colors.cta : colors.inkSoft,
              }}
            >
              {pin.wantCount}
            </span>
          )}
        </button>
      )}
      {wantError && (
        <div style={{ ...inlineErrorStyle, width: "100%" }}>{wantError}</div>
      )}
    </div>
  );

  const footer = mode === "edit" ? editFooter : viewFooter;

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

  // Phase 9 FR-1: ⋮ 좌측 sibling 공유 버튼.
  // SpeechBubblePopup이 menuBtnSize(28/24)와 동일 컨테이너로 정렬하므로 동일 크기로 맞춤.
  const shareButton = (
    <button
      type="button"
      onClick={() => setShareOpen(true)}
      aria-label="공유"
      style={{
        width: 28,
        height: 28,
        borderRadius: 6,
        background: "transparent",
        border: "none",
        cursor: "pointer",
        color: colors.inkSoft,
        padding: 0,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <IconShare size={16} color={colors.inkSoft} />
    </button>
  );

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
        shareAction={shareButton}
        footerContent={footer}
      >
        {menuPopover}
      </SpeechBubblePopup>
      {shareOpen && (
        <PinShareSheet
          pin={pin}
          mapboxToken={mapboxToken}
          mapboxStyleUrl={mapboxStyleUrl}
          groupPins={groupPins}
          onClose={() => setShareOpen(false)}
        />
      )}
      {progressModalOpen && (
        <TagProgressModal
          isOpen={progressModalOpen}
          currentTag={pin.tag}
          wantCount={pin.wantCount}
          onClose={() => setProgressModalOpen(false)}
        />
      )}
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
