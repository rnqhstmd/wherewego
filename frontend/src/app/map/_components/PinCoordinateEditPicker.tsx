"use client";

import { useEffect, useState } from "react";
import type mapboxgl from "mapbox-gl";
import type { PinSummaryResponse } from "@/lib/api/types";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { colors, fonts } from "@/lib/design/tokens";

interface PinCoordinateEditPickerProps {
  map: mapboxgl.Map | null;
  // 향후 reverse geocoding 확장 여지를 위해 시그니처 유지 (현재 미사용).
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  mapboxToken: string;
  initialPin: PinSummaryResponse;
  onCancel: () => void;
  onConfirm: (latLng: { lat: number; lng: number }) => void;
}

/**
 * 핀 좌표 수정 picker — 지도 중심을 추적하여 현재 좌표를 표시하고
 * 완료 시 새 좌표를 콜백한다. placeName/address 는 보존(reverse geocoding 미호출).
 */
export default function PinCoordinateEditPicker({
  map,
  initialPin,
  onCancel,
  onConfirm,
}: PinCoordinateEditPickerProps) {
  const [coord, setCoord] = useState<{ lng: number; lat: number }>({
    lng: Number(initialPin.longitude),
    lat: Number(initialPin.latitude),
  });

  // 지도 중심 좌표 추적 (AddPinPickerContent 패턴 그대로).
  useEffect(() => {
    if (!map) return;
    const updateCenter = () => {
      const c = map.getCenter();
      setCoord({ lng: c.lng, lat: c.lat });
    };
    updateCenter();
    map.on("move", updateCenter);
    return () => {
      map.off("move", updateCenter);
    };
  }, [map]);

  return (
    <div>
      <div
        style={{
          fontSize: 13,
          color: colors.inkSoft,
          fontFamily: fonts.sans,
          marginBottom: 6,
        }}
      >
        이 핀의 위치를 옮겨주세요
      </div>
      <div
        style={{
          fontSize: 14,
          color: colors.ink,
          fontFamily: fonts.serif,
          fontWeight: 700,
          marginBottom: 10,
          wordBreak: "break-word",
        }}
      >
        {initialPin.placeName}
      </div>
      <div
        style={{
          fontSize: 13,
          color: colors.inkSoft,
          marginBottom: 10,
          fontFamily: fonts.mono,
          minHeight: 20,
          wordBreak: "break-word",
        }}
      >
        📍 {coord.lat.toFixed(7)}, {coord.lng.toFixed(7)}
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub onClick={onCancel} style={{ flex: 1, padding: "11px 0" }}>
          취소
        </BtnSub>
        <BtnPrimary
          onClick={() => onConfirm({ lat: coord.lat, lng: coord.lng })}
          style={{ flex: 1, padding: "11px 0" }}
        >
          완료
        </BtnPrimary>
      </div>
    </div>
  );
}
