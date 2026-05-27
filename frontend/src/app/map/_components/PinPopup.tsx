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
    // 후속(UX 재반영): WANT 토글은 REEL 핀에서만 동작. WISH/MEMORY 는 노출 자체 안 함.
    if (!onWantToggle || wantPending || pin.tag !== "REEL") return;
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

  // Phase 12 후속(UX 개선, 재반영):
  //  - 출처 뱃지(📹/✏️), 도움말(?) 모두 PinPopup 에서 제거 — 도움말은 좌하단 ! (TagLegendButton) 통합.
  //  - WANT 하트는 무신사 스타일로 place row 우측에 배치 (좌측 count + 우측 하트 아이콘).
  //  - WANT 에러는 view 모드 inline footer 가 더 이상 없으므로 본문 하단 별도 영역에 노출한다.
  const viewFooter = wantError ? (
    <div style={inlineErrorStyle}>{wantError}</div>
  ) : null;

  // place row 우측 하트 (WANT). 후속(UX 재반영): REEL 핀에만 노출(WISH/MEMORY 모두 숨김).
  // - WISH 는 이미 둘 다 가고 싶어한 결과 상태라 추가 액션 불필요.
  // - MEMORY 는 다녀온 곳이라 가고 싶어요 의미가 없음.
  const bodyHeart =
    pin.tag === "REEL" && onWantToggle ? (
      <HeartAction
        myWant={pin.myWant}
        wantCount={pin.wantCount}
        pending={wantPending}
        onToggle={handleWantToggle}
      />
    ) : undefined;

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
        bodyAction={mode === "view" ? bodyHeart : undefined}
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

/**
 * Phase 12 후속(UX 재반영2): 무신사 스타일 WANT 하트 액션 — 작은 크기 + 얇은 선 + vivid red.
 *  - 좌측: 받은 하트 카운트 (작은 회색 텍스트, 0 이면 미노출)
 *  - 우측: 하트 아이콘 16px, strokeWidth 1.3, 활성 시 #FF2D55(vivid red)
 *  - hover/focus 시 안내 툴팁 노출.
 *  - MEMORY 핀에는 호출부에서 미노출.
 */
const WANT_RED = "#FF2D55";

function HeartAction({
  myWant,
  wantCount,
  pending,
  onToggle,
}: {
  myWant: boolean;
  wantCount: number;
  pending: boolean;
  onToggle: () => void;
}) {
  const [hover, setHover] = useState(false);
  // 커플(2인) 그룹 톤 — count/내 상태별 자연어 분기.
  // - 아무도 안 누름: "가고 싶어요" (기본 권유)
  // - 애인만 누름:   "애인이 가고 싶어해요"
  // - 나만 누름:     "가고 싶다고 표시했어요"
  // - 둘 다:         "둘 다 가고 싶어해요" (WISH 직전 — 잠깐만 보임)
  const tooltipText = myWant
    ? wantCount > 1
      ? "둘 다 가고 싶어해요"
      : "가고 싶다고 표시했어요"
    : wantCount > 0
      ? "애인이 가고 싶어해요"
      : "가고 싶어요";

  return (
    <div
      style={{ position: "relative", display: "inline-flex" }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <button
        type="button"
        onClick={onToggle}
        disabled={pending}
        aria-label={tooltipText}
        aria-pressed={myWant}
        onFocus={() => setHover(true)}
        onBlur={() => setHover(false)}
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: 4,
          padding: "2px 2px",
          background: "transparent",
          border: "none",
          cursor: pending ? "wait" : "pointer",
          color: myWant ? WANT_RED : colors.inkSoft,
          opacity: pending ? 0.6 : 1,
        }}
      >
        {wantCount > 0 && (
          <span
            style={{
              fontFamily: fonts.mono,
              fontSize: 11,
              fontWeight: 600,
              color: myWant ? WANT_RED : colors.inkSoft,
              lineHeight: 1,
              minWidth: 6,
              textAlign: "right",
            }}
          >
            {wantCount}
          </span>
        )}
        <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M12 21s-7.5-4.6-9.5-9.1C1 7.7 3.6 4 7.3 4c2 0 3.5 1.1 4.7 2.7C13.2 5.1 14.7 4 16.7 4c3.7 0 6.3 3.7 4.8 7.9C19.5 16.4 12 21 12 21z"
            fill={myWant ? WANT_RED : "none"}
            stroke={myWant ? WANT_RED : "currentColor"}
            strokeWidth="1.3"
            strokeLinejoin="round"
          />
        </svg>
      </button>
      {hover && (
        <span
          role="tooltip"
          style={{
            position: "absolute",
            top: "calc(100% + 6px)",
            right: 0,
            whiteSpace: "nowrap",
            padding: "5px 9px",
            borderRadius: 8,
            background: colors.ink,
            color: "#fff",
            fontFamily: fonts.sans,
            fontSize: 11,
            fontWeight: 600,
            boxShadow: `0 4px 12px ${colors.shadow}`,
            pointerEvents: "none",
            zIndex: 30,
          }}
        >
          {tooltipText}
        </span>
      )}
    </div>
  );
}



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
