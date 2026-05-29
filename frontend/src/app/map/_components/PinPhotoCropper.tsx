"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Cropper, { type Area, type Point } from "react-easy-crop";
import { colors, fonts } from "@/lib/design/tokens";
import { getCroppedSquareFile } from "@/lib/image/cropImage";

interface PinPhotoCropperProps {
  /** 사용자가 선택한 원본 파일. */
  file: File;
  /** 정사각 크롭이 확정된 결과 File 을 전달한다. */
  onConfirm: (cropped: File) => void | Promise<void>;
  /** 크롭 취소 (모달 닫기 + 파일 초기화). */
  onCancel: () => void;
}

/**
 * 작업 1: 추억핀 사진 업로드 1:1 크롭 모달.
 *
 * 파일 선택 직후 띄워 사용자가 `react-easy-crop` 으로 정사각(aspect=1) 영역을 직접 고른다.
 * 드래그 pan + 데스크톱 휠 / 모바일 핀치 줌(별도 슬라이더 없음). "확인" 시 `croppedAreaPixels` 로 canvas 크롭해
 * 정사각 JPEG File 을 만들어 `onConfirm` 으로 넘긴다(이후 호출처가 compress + 업로드).
 *
 * 모달 오버레이(어두운 배경 + 중앙 카드). 인라인 style + colors/fonts 토큰.
 */
export default function PinPhotoCropper({
  file,
  onConfirm,
  onCancel,
}: PinPhotoCropperProps) {
  const [imageSrc, setImageSrc] = useState<string | null>(null);
  const [crop, setCrop] = useState<Point>({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const croppedAreaPixelsRef = useRef<Area | null>(null);

  // 선택 파일 → object URL (모달이 닫히면 정리).
  useEffect(() => {
    const url = URL.createObjectURL(file);
    setImageSrc(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  const handleCropComplete = useCallback(
    (_croppedArea: Area, croppedAreaPixels: Area) => {
      croppedAreaPixelsRef.current = croppedAreaPixels;
    },
    [],
  );

  const handleConfirm = async () => {
    if (processing) return;
    const area = croppedAreaPixelsRef.current;
    if (!area) return;
    setError(null);
    setProcessing(true);
    try {
      const cropped = await getCroppedSquareFile(file, area);
      await onConfirm(cropped);
    } catch {
      setError("사진을 자르지 못했어요. 다시 시도해 주세요.");
      setProcessing(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="사진 자르기"
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 100,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "rgba(26,26,46,0.55)",
        padding: 16,
        fontFamily: fonts.sans,
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 360,
          background: colors.panel,
          borderRadius: 18,
          boxShadow: `0 18px 48px ${colors.shadowMd}`,
          overflow: "hidden",
          display: "flex",
          flexDirection: "column",
        }}
      >
        <div
          style={{
            padding: "14px 18px",
            fontSize: 14,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -0.2,
          }}
        >
          사진 자르기
        </div>

        {/* 크롭 영역 — 정사각 프레임 (aspect=1). */}
        <div
          style={{
            position: "relative",
            width: "100%",
            aspectRatio: "1 / 1",
            background: colors.ink,
          }}
        >
          {imageSrc ? (
            <Cropper
              image={imageSrc}
              crop={crop}
              zoom={zoom}
              aspect={1}
              minZoom={1}
              maxZoom={3}
              showGrid
              restrictPosition
              onCropChange={setCrop}
              onZoomChange={setZoom}
              onCropComplete={handleCropComplete}
            />
          ) : null}
        </div>

        {error ? (
          <div
            role="alert"
            style={{
              padding: "0 18px",
              fontSize: 12,
              color: colors.pinNew,
            }}
          >
            {error}
          </div>
        ) : null}

        {/* 확인 / 취소 */}
        <div
          style={{
            display: "flex",
            justifyContent: "flex-end",
            gap: 8,
            padding: "12px 18px 16px",
          }}
        >
          <button
            type="button"
            onClick={onCancel}
            disabled={processing}
            style={{
              padding: "8px 16px",
              borderRadius: 10,
              border: `1px solid ${colors.hairline}`,
              background: colors.panel,
              color: colors.inkSoft,
              fontFamily: fonts.sans,
              fontSize: 13,
              fontWeight: 600,
              cursor: processing ? "default" : "pointer",
            }}
          >
            취소
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={processing}
            style={{
              padding: "8px 18px",
              borderRadius: 10,
              border: "none",
              background: colors.cta,
              color: colors.panel,
              fontFamily: fonts.sans,
              fontSize: 13,
              fontWeight: 700,
              cursor: processing ? "default" : "pointer",
              opacity: processing ? 0.7 : 1,
            }}
          >
            {processing ? "처리 중..." : "확인"}
          </button>
        </div>
      </div>
    </div>
  );
}
