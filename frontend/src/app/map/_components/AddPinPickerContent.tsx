"use client";

import { useEffect, useState } from "react";
import type mapboxgl from "mapbox-gl";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { colors, fonts } from "@/lib/design/tokens";

interface AddPinPickerContentProps {
  map: mapboxgl.Map | null;
  onCancel: () => void;
  onConfirm: (coords: { lng: number; lat: number }) => void;
}

/**
 * Crosshair 모드 — 지도 중심 좌표를 표시하고 "취소/완료" 버튼.
 *
 * 지도 이동(moveend)마다 중심 좌표를 갱신해 표시한다.
 * 완료 시 현재 중심 좌표를 MapClient 로 콜백.
 */
export default function AddPinPickerContent({
  map,
  onCancel,
  onConfirm,
}: AddPinPickerContentProps) {
  const [center, setCenter] = useState<{ lng: number; lat: number } | null>(
    null,
  );

  useEffect(() => {
    if (!map) return;
    const updateCenter = () => {
      const c = map.getCenter();
      setCenter({ lng: c.lng, lat: c.lat });
    };
    updateCenter();
    map.on("moveend", updateCenter);
    return () => {
      map.off("moveend", updateCenter);
    };
  }, [map]);

  return (
    <div>
      <div
        style={{
          fontSize: 13,
          color: colors.inkSoft,
          marginBottom: 10,
          fontFamily: fonts.mono,
        }}
      >
        📍{" "}
        {center
          ? `${center.lat.toFixed(6)}, ${center.lng.toFixed(6)}`
          : "지도를 이동해 위치를 선택해주세요"}
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub onClick={onCancel} style={{ flex: 1, padding: "11px 0" }}>
          취소
        </BtnSub>
        <BtnPrimary
          onClick={() => center && onConfirm(center)}
          disabled={!center}
          style={{ flex: 1, padding: "11px 0" }}
        >
          완료
        </BtnPrimary>
      </div>
    </div>
  );
}
