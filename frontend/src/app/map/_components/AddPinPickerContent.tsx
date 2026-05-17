"use client";

import { useEffect, useRef, useState } from "react";
import type mapboxgl from "mapbox-gl";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { colors, fonts } from "@/lib/design/tokens";
import { reverseGeocode } from "../_lib/reverseGeocode";

interface AddPinPickerContentProps {
  map: mapboxgl.Map | null;
  mapboxToken: string;
  onCancel: () => void;
  onConfirm: (origin: {
    lng: number;
    lat: number;
    address: string | null;
    placeName: string | null;
  }) => void;
}

const DEBOUNCE_MS = 500;

/**
 * Crosshair 모드 — 지도 중심 좌표를 표시하고 Mapbox reverse geocoding 으로 주소를 보여준다.
 *
 * 정책: 한국 좌표는 한국어, 외국은 영어 주소 (reverseGeocode 내부 분기).
 * 좌표 변경 시 디바운스 + AbortController 로 stale 응답 차단.
 * 완료 시 origin(좌표 + 주소 + placeName)을 MapClient 로 콜백.
 */
export default function AddPinPickerContent({
  map,
  mapboxToken,
  onCancel,
  onConfirm,
}: AddPinPickerContentProps) {
  const [center, setCenter] = useState<{ lng: number; lat: number } | null>(
    null,
  );
  const [address, setAddress] = useState<string | null>(null);
  const [placeName, setPlaceName] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // 지도 중심 좌표 추적
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

  // 좌표 변경 시 디바운스 reverse geocoding
  useEffect(() => {
    if (!center) return;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (abortRef.current) abortRef.current.abort();

    setLoading(true);
    debounceRef.current = setTimeout(() => {
      const controller = new AbortController();
      abortRef.current = controller;
      reverseGeocode(center.lng, center.lat, mapboxToken, controller.signal)
        .then((result) => {
          if (controller.signal.aborted) return;
          setAddress(result.address);
          setPlaceName(result.placeName);
        })
        .catch((e) => {
          if (controller.signal.aborted) return;
          if (e instanceof Error && e.name === "AbortError") return;
          // 실패 시 좌표 fallback — address/placeName 은 null 유지
          setAddress(null);
          setPlaceName(null);
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false);
        });
    }, DEBOUNCE_MS);

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [center, mapboxToken]);

  // 언마운트 시 in-flight 요청 취소
  useEffect(
    () => () => {
      if (abortRef.current) abortRef.current.abort();
      if (debounceRef.current) clearTimeout(debounceRef.current);
    },
    [],
  );

  const coordLabel = center
    ? `${center.lat.toFixed(6)}, ${center.lng.toFixed(6)}`
    : null;

  const displayLine = loading
    ? "주소를 찾는 중..."
    : address || coordLabel || "지도를 이동해 위치를 선택해주세요";

  return (
    <div>
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
        📍 {displayLine}
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub onClick={onCancel} style={{ flex: 1, padding: "11px 0" }}>
          취소
        </BtnSub>
        <BtnPrimary
          onClick={() =>
            center &&
            onConfirm({
              lng: center.lng,
              lat: center.lat,
              address,
              placeName,
            })
          }
          disabled={!center}
          style={{ flex: 1, padding: "11px 0" }}
        >
          완료
        </BtnPrimary>
      </div>
    </div>
  );
}
