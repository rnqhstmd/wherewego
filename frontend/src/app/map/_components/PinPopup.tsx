"use client";

import { useEffect, useRef, useState } from "react";
import type mapboxgl from "mapbox-gl";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";
import { SpeechBubblePopup } from "@/components/ui/SpeechBubblePopup";
import { PinTag as PinTagChip } from "@/components/ui/PinTag";
import type { PinDotType } from "@/components/ui/PinDot";
import { IconShare } from "@/components/icons";
import { colors, fonts } from "@/lib/design/tokens";
import PinPopupMemoEditor from "./PinPopupMemoEditor";
import PinShareSheet from "./PinShareSheet";
import PinPhotoUploader from "./PinPhotoUploader";
import PinPhotoInline from "./PinPhotoInline";

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
   * Phase 13 (FR-PIN-9g~k): MEMORY 핀 사진 업로드/삭제. MapClient 가 주입한 핸들러로 위임한다.
   * (압축된 File 전달, 핀 갱신은 MapClient reducer update 책임)
   */
  onPhotoUpload?: (pinId: number, file: File) => Promise<void>;
  onPhotoDelete?: (pinId: number) => Promise<void>;
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
  onPhotoUpload,
  onPhotoDelete,
}: PinPopupProps) {
  const [screenPos, setScreenPos] = useState<{ x: number; y: number } | null>(
    null,
  );
  const [mode, setMode] = useState<PopupMode>("view");
  const [editTab, setEditTab] = useState<EditTab>("place");
  // 수정은 장소→태그→메모 위저드. 각 필드는 draft 로만 바꾸고, 마지막 메모 탭의 "저장"이
  // 변경된 필드(장소/태그/메모)를 한 번에 커밋한다. 저장 진행/에러는 saving/saveError 로 통합.
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [placeDraft, setPlaceDraft] = useState(pin.placeName);
  // 장소 탭 "다음" 시 빈 값 검증용 인라인 에러.
  const [placeError, setPlaceError] = useState<string | null>(null);
  const [tagDraft, setTagDraft] = useState<PinTag>(pin.tag);
  // Phase 9: 공유 카드 시트 표시 여부.
  const [shareOpen, setShareOpen] = useState(false);
  // Phase 13 후속: 말풍선 안 사진 제자리 펼침 여부 + 사진 업로드/삭제 진행 표시.
  const [photoExpanded, setPhotoExpanded] = useState(false);
  const [photoUploading, setPhotoUploading] = useState(false);
  // Phase 13 후속: 사진 변경을 즉시 반영하지 않고 staging 한다 — 저장 시 일괄 반영, 취소 시 폐기.
  // pendingPhotoFile: 저장 시 업로드할 새 파일. pendingPhotoRemoved: 저장 시 기존 사진 삭제.
  const [pendingPhotoFile, setPendingPhotoFile] = useState<File | null>(null);
  const [pendingPhotoRemoved, setPendingPhotoRemoved] = useState(false);

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
    setSaving(false);
    setSaveError(null);
    setPlaceDraft(pin.placeName);
    setPlaceError(null);
    setTagDraft(pin.tag);
    setShareOpen(false);
    setPhotoExpanded(false);
    setPhotoUploading(false);
    setPendingPhotoFile(null);
    setPendingPhotoRemoved(false);
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

  // 장소 탭 "다음" — 빈 장소면 막고, 아니면 태그 탭으로 진행.
  const handleNextFromPlace = () => {
    if (!placeDraft.trim()) {
      setPlaceError("장소 이름을 비울 수 없어요");
      return;
    }
    setPlaceError(null);
    setEditTab("tag");
  };

  // 수정 모드 취소 — 모든 draft 를 원래 값으로 되돌리고 view 로.
  const handleCancelEdit = () => {
    setPlaceDraft(pin.placeName);
    setTagDraft(pin.tag);
    setPlaceError(null);
    setSaveError(null);
    // staged 사진 변경 폐기 — 아직 업로드/삭제하지 않았으므로 되돌릴 것이 없다.
    setPendingPhotoFile(null);
    setPendingPhotoRemoved(false);
    setMode("view");
  };

  // 위저드 최종 저장(메모 탭) — 변경된 장소/태그/메모를 순서대로 커밋한다.
  // 백엔드가 필드별 API 라 변경분만 호출하고, 일부 실패 시 메시지를 합쳐 보여준다(이미 성공한 건 유지).
  const handleSaveAll = async (nextMemo: string) => {
    if (saving) return;
    const trimmedPlace = placeDraft.trim();
    if (!trimmedPlace) {
      setSaveError("장소 이름을 비울 수 없어요");
      setEditTab("place");
      return;
    }
    const placeChanged = trimmedPlace !== pin.placeName;
    const tagChanged = tagDraft !== pin.tag;
    const memoChanged = nextMemo !== (pin.memo ?? "");
    const photoRemoved = pendingPhotoRemoved && !!pin.photoUrl;
    const photoAdded = pendingPhotoFile !== null;
    if (
      !placeChanged &&
      !tagChanged &&
      !memoChanged &&
      !photoRemoved &&
      !photoAdded
    ) {
      setMode("view");
      return;
    }
    setSaving(true);
    setSaveError(null);
    const errors: string[] = [];
    if (placeChanged) {
      const r = await onPlaceNameChange(pin.id, trimmedPlace);
      if (!mountedRef.current) return;
      if (!r.ok) errors.push(r.message ?? "장소 저장에 실패했어요");
    }
    // 사진 삭제는 태그 변경보다 먼저 — 비-MEMORY 핀에 사진이 남아 있으면 백엔드가 거부한다.
    // onPhotoDelete 는 실패 시 자체 토스트로 안내하고 throw 하지 않는다(BR-6).
    if (photoRemoved && onPhotoDelete) {
      setPhotoUploading(true);
      await onPhotoDelete(pin.id);
      if (!mountedRef.current) return;
      setPhotoUploading(false);
    }
    if (tagChanged) {
      const r = await onTagChange(pin.id, tagDraft);
      if (!mountedRef.current) return;
      if (!r.ok) errors.push(r.message ?? "태그 저장에 실패했어요");
    }
    // 사진 추가는 태그가 MEMORY 로 확정된 뒤 — 가드가 비-MEMORY+사진 조합을 막는다.
    if (pendingPhotoFile && onPhotoUpload) {
      setPhotoUploading(true);
      await onPhotoUpload(pin.id, pendingPhotoFile);
      if (!mountedRef.current) return;
      setPhotoUploading(false);
    }
    if (memoChanged) {
      const r = await onMemoChange(pin.id, nextMemo);
      if (!mountedRef.current) return;
      if (!r.ok) errors.push(r.message ?? "메모 저장에 실패했어요");
    }
    setSaving(false);
    if (errors.length > 0) {
      setSaveError(errors.join(" / "));
    } else {
      setPendingPhotoFile(null);
      setPendingPhotoRemoved(false);
      setMode("view");
    }
  };

  const handleMenuClick = () => {
    setMode((prev) =>
      prev === "menu" ? "view" : prev === "edit" ? "view" : "menu",
    );
  };

  // Phase 13 후속: 사진 변경은 staging 만 한다 (즉시 업로드/삭제 금지).
  // 실제 반영은 handleSaveAll, 폐기는 handleCancelEdit 가 담당하며,
  // PinPhotoUploader 가 선택본의 로컬 미리보기를 자체 관리한다.
  const handlePhotoUpload = (file: File) => {
    setPendingPhotoFile(file);
    setPendingPhotoRemoved(false);
  };

  const handlePhotoDelete = () => {
    setPendingPhotoFile(null);
    // 기존 업로드된 사진이 있을 때만 삭제를 staging (없으면 staged 추가 취소로 충분).
    setPendingPhotoRemoved(Boolean(pin.photoUrl));
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
        onChange={(e) => {
          setPlaceDraft(e.target.value);
          if (placeError) setPlaceError(null);
        }}
        disabled={saving}
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
      {placeError && (
        <div style={{ ...inlineErrorStyle, marginTop: 6 }}>{placeError}</div>
      )}
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
          onClick={handleNextFromPlace}
          style={{ ...linkButtonStyle(colors.cta), fontWeight: 700 }}
        >
          다음 →
        </button>
      </div>
    </div>
  );

  // 사진이 붙은(또는 저장 시 붙을) 추억핀은 위시/발견으로 바꿀 수 없다 — 비-MEMORY 핀은 사진 불가.
  // 변경하려면 먼저 사진을 삭제(staging)해야 한다.
  const photoPresentForTag =
    pendingPhotoFile !== null || (!!pin.photoUrl && !pendingPhotoRemoved);
  const tagPanel = (
    <div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <PinTagChip
          type="MEMORY"
          active={tagDraft === "MEMORY"}
          disabled={saving}
          onClick={() => setTagDraft("MEMORY")}
        />
        <PinTagChip
          type="WISH"
          active={tagDraft === "WISH"}
          disabled={saving || photoPresentForTag}
          onClick={() => {
            if (photoPresentForTag) return;
            setTagDraft("WISH");
          }}
        />
        <PinTagChip
          type="REEL"
          active={tagDraft === "REEL"}
          disabled={saving || photoPresentForTag}
          onClick={() => {
            if (photoPresentForTag) return;
            setTagDraft("REEL");
          }}
        />
      </div>
      {photoPresentForTag ? (
        <div
          style={{
            marginTop: 8,
            fontFamily: fonts.sans,
            fontSize: 12,
            color: colors.inkSoft,
          }}
        >
          사진이 있는 추억핀이에요. 위시·발견으로 바꾸려면 먼저 사진을 삭제해 주세요.
        </div>
      ) : null}
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          marginTop: 10,
        }}
      >
        <button
          type="button"
          onClick={() => setEditTab("place")}
          style={linkButtonStyle(colors.inkSoft)}
        >
          ← 이전
        </button>
        <button
          type="button"
          onClick={() => setEditTab("memo")}
          style={{ ...linkButtonStyle(colors.cta), fontWeight: 700 }}
        >
          다음 →
        </button>
      </div>
    </div>
  );

  const memoPanel = (
    <div>
      <PinPopupMemoEditor
        key={pin.id}
        initialMemo={pin.memo}
        pending={saving}
        error={saveError}
        onSave={handleSaveAll}
        onCancel={handleCancelEdit}
        alsoDirty={
          placeDraft.trim() !== pin.placeName ||
          tagDraft !== pin.tag ||
          pendingPhotoFile !== null ||
          (pendingPhotoRemoved && !!pin.photoUrl)
        }
      >
        {/* Phase 13 (Q7): 메모 입력과 취소/저장 사이에 MEMORY 핀 전용 사진 업로더.
            취소/저장 버튼이 항상 맨 아래에 오도록 에디터 children 슬롯으로 주입한다.
            사진 변경은 staging 만 — 실제 업로드/삭제는 저장 시 일괄 반영된다. */}
        {pin.tag === "MEMORY" && tagDraft === "MEMORY" && onPhotoUpload ? (
          <div style={{ marginTop: 12 }}>
            <PinPhotoUploader
              // staged 삭제 시 기존 URL 을 숨겨 빈 슬롯으로 보이게 한다.
              photoUrl={pendingPhotoRemoved ? null : pin.photoUrl}
              thumbnailUrl={pendingPhotoRemoved ? null : pin.photoThumbnailUrl}
              onFileSelected={handlePhotoUpload}
              onDelete={onPhotoDelete ? handlePhotoDelete : undefined}
              uploading={photoUploading}
            />
          </div>
        ) : null}
      </PinPopupMemoEditor>
    </div>
  );

  const editFooter = (
    <div>
      {tabHeader}
      {editTab === "place"
        ? placePanel
        : editTab === "tag"
          ? tagPanel
          : memoPanel}
      {/* 보조 액션: 좌표 수정 / 닫기. 닫기는 수정 중 draft 를 되돌리고 view 로(메모 탭의 최종 저장만 커밋). */}
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
          onClick={handleCancelEdit}
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

  // Phase 13 후속 (FR-PIN-11a): 말풍선 메모 우측 정사각 썸네일. MEMORY + 사진이 있을 때만.
  // 클릭 시 메모 영역을 제자리에서 사진으로 펼친다. 사진 없으면 undefined →
  // SpeechBubblePopup 레이아웃 불변(AC-11).
  const hasPhoto = Boolean(
    pin.tag === "MEMORY" && pin.photoThumbnailUrl && pin.photoUrl,
  );
  const memoThumbnail =
    pin.tag === "MEMORY" && pin.photoThumbnailUrl ? (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={pin.photoThumbnailUrl}
        alt="추억 사진"
        loading="lazy"
        onClick={() => setPhotoExpanded(true)}
        style={{
          width: 36,
          height: 36,
          borderRadius: 9,
          objectFit: "cover",
          cursor: "pointer",
        }}
      />
    ) : undefined;

  // 메모 영역을 제자리에서 대체하는 1:1 사진 노드 (blur-up + ↩ 복귀).
  const expandedPhoto =
    hasPhoto && pin.photoThumbnailUrl && pin.photoUrl ? (
      <PinPhotoInline
        thumbnailUrl={pin.photoThumbnailUrl}
        photoUrl={pin.photoUrl}
        onBack={() => setPhotoExpanded(false)}
      />
    ) : undefined;

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
        memoThumbnail={memoThumbnail}
        expandedPhoto={expandedPhoto}
        showExpandedPhoto={photoExpanded}
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
