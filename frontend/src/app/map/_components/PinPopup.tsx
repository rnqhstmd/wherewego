"use client";

import { useEffect, useRef, useState } from "react";
import type mapboxgl from "mapbox-gl";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";
import { SpeechBubblePopup } from "@/components/ui/SpeechBubblePopup";
import { PinTag as PinTagChip } from "@/components/ui/PinTag";
import { colors, fonts } from "@/lib/design/tokens";
import PinPopupMemoEditor from "./PinPopupMemoEditor";

interface PinPopupProps {
  pin: PinSummaryResponse;
  map: mapboxgl.Map | null;
  authorLabel?: string;
  /**
   * 태그 변경 콜백 (MUST-5). MapClient 에서 useOptimistic + updatePinTagAction 호출.
   * 응답으로 성공/실패와 표시할 에러 메시지를 반환한다.
   */
  onTagChange: (
    pinId: number,
    nextTag: PinTag,
  ) => Promise<{ ok: boolean; message?: string }>;
  /**
   * 메모 변경 콜백 (FR-MMO-2). MapClient 에서 useOptimistic + updatePinMemoAction 호출.
   * 응답으로 성공/실패와 표시할 에러 메시지를 반환한다.
   */
  onMemoChange: (
    pinId: number,
    nextMemo: string,
  ) => Promise<{ ok: boolean; message?: string }>;
}

type PopupView = "tag" | "memo";

/**
 * ⋮ 펼침의 초기 뷰 선택 (디스커버러빌리티):
 * 메모가 비어있는 핀은 사용자가 ⋮ 첫 클릭 시 곧바로 메모 편집 화면으로 진입한다.
 */
const initialView = (p: PinSummaryResponse): PopupView =>
  p.memo && p.memo.length > 0 ? "tag" : "memo";

/**
 * 선택된 핀의 [lng, lat]을 map.project로 화면 좌표 변환 후 SpeechBubblePopup 렌더.
 *
 * Mapbox 내장 Popup은 사용하지 않는다 (설계 §9). 지도가 이동/줌될 때
 * `move`/`zoom` 이벤트로 좌표를 재계산하여 말풍선 위치를 갱신한다.
 *
 * 배치 5 (MUST-5): ⋮ 클릭 → 하단 인라인 PinTag 칩 2개 펼침 →
 * 다른 태그 클릭 시 `onTagChange` 호출 → MapClient 의 useOptimistic 이
 * 마커 모양을 즉시 갱신. 실패 시 인라인 에러 메시지 노출.
 *
 * Phase 2.6 PR-A: ⋮ 펼침을 "태그 / 메모" 2뷰 세그먼트 탭으로 확장 (FR-MMO-2).
 * 메모 비어있는 핀은 ⋮ 첫 클릭 시 "memo" 탭으로 자동 진입한다.
 */
export default function PinPopup({
  pin,
  map,
  authorLabel,
  onTagChange,
  onMemoChange,
}: PinPopupProps) {
  const [screenPos, setScreenPos] = useState<{ x: number; y: number } | null>(
    null,
  );
  const [expanded, setExpanded] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<PopupView>(() => initialView(pin));
  const [memoPending, setMemoPending] = useState(false);
  const [memoError, setMemoError] = useState<string | null>(null);

  // 언마운트 후 in-flight 응답이 setState 를 호출하지 않도록 가드 (React 경고 회피).
  const mountedRef = useRef(true);
  useEffect(
    () => () => {
      mountedRef.current = false;
    },
    [],
  );

  // 다른 핀으로 전환되면 메뉴 펼침/에러/뷰를 렌더 중 분기로 초기화한다
  // (react-hooks/set-state-in-effect 회피 — React 공식 권장 패턴).
  const [trackedPinId, setTrackedPinId] = useState<number>(pin.id);
  if (trackedPinId !== pin.id) {
    setTrackedPinId(pin.id);
    setExpanded(false);
    setError(null);
    setView(initialView(pin));
    setMemoPending(false);
    setMemoError(null);
  }

  useEffect(() => {
    if (!map) return;

    const lng = Number(pin.longitude);
    const lat = Number(pin.latitude);

    const updatePos = () => {
      const point = map.project([lng, lat]);
      setScreenPos({ x: point.x, y: point.y });
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
      setView("tag");
    } else {
      setMemoError(result.message ?? "메모 저장에 실패했어요");
    }
  };

  const renderSegmentButton = (target: PopupView, label: string) => {
    const active = view === target;
    return (
      <button
        type="button"
        onClick={() => setView(target)}
        style={{
          height: 26,
          padding: "4px 12px",
          borderRadius: 999,
          border: "none",
          background: active ? colors.ink : "transparent",
          color: active ? "#fff" : colors.inkSoft,
          fontFamily: fonts.sans,
          fontSize: 12,
          fontWeight: 600,
          cursor: "pointer",
        }}
      >
        {label}
      </button>
    );
  };

  const tagBody = (
    <div>
      <div
        style={{
          display: "flex",
          gap: 8,
          alignItems: "center",
          marginBottom: error ? 8 : 0,
        }}
      >
        <PinTagChip
          type="place"
          active={pin.tag === "PLACE"}
          disabled={pending}
          onClick={() => handleTagToggle("PLACE")}
        />
        <PinTagChip
          type="memory"
          active={pin.tag === "MEMORY"}
          disabled={pending}
          onClick={() => handleTagToggle("MEMORY")}
        />
        {pending && (
          <span
            style={{
              fontFamily: fonts.sans,
              fontSize: 12,
              color: colors.inkSoft,
            }}
          >
            저장 중...
          </span>
        )}
      </div>
      {error && (
        <div
          style={{
            fontFamily: fonts.sans,
            fontSize: 12,
            color: colors.pinNew,
          }}
        >
          {error}
        </div>
      )}
    </div>
  );

  const footer = expanded ? (
    <div>
      <div
        style={{
          display: "flex",
          gap: 6,
          marginBottom: 10,
        }}
      >
        {renderSegmentButton("tag", "태그")}
        {renderSegmentButton("memo", "메모")}
      </div>
      {view === "tag" ? (
        tagBody
      ) : (
        <PinPopupMemoEditor
          key={pin.id}
          initialMemo={pin.memo}
          pending={memoPending}
          error={memoError}
          onSave={handleSaveMemo}
          onCancel={() => setView("tag")}
        />
      )}
    </div>
  ) : null;

  return (
    <SpeechBubblePopup
      pinX={screenPos.x}
      pinY={screenPos.y}
      memo={pin.memo ?? ""}
      place={pin.placeName}
      addr={pin.address ?? ""}
      author={authorLabel ?? String(pin.createdBy)}
      date={formattedDate}
      pinType={pin.tag === "MEMORY" ? "memory" : "place"}
      onMenuClick={() => {
        // Strict Mode 이중 호출 안전성: updater 는 순수하게 유지하고,
        // 닫는 시점(현재 expanded=true) 판단은 외부에서 수행한다.
        if (expanded) {
          setView(initialView(pin));
        }
        setExpanded((prev) => !prev);
      }}
      footerContent={footer}
    />
  );
}
