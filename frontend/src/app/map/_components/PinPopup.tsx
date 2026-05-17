"use client";

import { useEffect, useState } from "react";
import type mapboxgl from "mapbox-gl";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";
import { SpeechBubblePopup } from "@/components/ui/SpeechBubblePopup";
import { PinTag as PinTagChip } from "@/components/ui/PinTag";
import { colors, fonts } from "@/lib/design/tokens";

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
}

/**
 * 선택된 핀의 [lng, lat]을 map.project로 화면 좌표 변환 후 SpeechBubblePopup 렌더.
 *
 * Mapbox 내장 Popup은 사용하지 않는다 (설계 §9). 지도가 이동/줌될 때
 * `move`/`zoom` 이벤트로 좌표를 재계산하여 말풍선 위치를 갱신한다.
 *
 * 배치 5 (MUST-5): ⋮ 클릭 → 하단 인라인 PinTag 칩 2개 펼침 →
 * 다른 태그 클릭 시 `onTagChange` 호출 → MapClient 의 useOptimistic 이
 * 마커 모양을 즉시 갱신. 실패 시 인라인 에러 메시지 노출.
 */
export default function PinPopup({
  pin,
  map,
  authorLabel,
  onTagChange,
}: PinPopupProps) {
  const [screenPos, setScreenPos] = useState<{ x: number; y: number } | null>(
    null,
  );
  const [expanded, setExpanded] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 다른 핀으로 전환되면 메뉴 펼침/에러를 렌더 중 분기로 초기화한다
  // (react-hooks/set-state-in-effect 회피 — React 공식 권장 패턴).
  const [trackedPinId, setTrackedPinId] = useState<number>(pin.id);
  if (trackedPinId !== pin.id) {
    setTrackedPinId(pin.id);
    setExpanded(false);
    setError(null);
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
    setPending(false);
    if (!result.ok) {
      setError(result.message ?? "태그 변경에 실패했어요");
    }
  };

  const footer = expanded ? (
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
      onMenuClick={() => setExpanded((prev) => !prev)}
      footerContent={footer}
    />
  );
}
