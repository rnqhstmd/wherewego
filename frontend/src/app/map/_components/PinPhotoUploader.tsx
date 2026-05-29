"use client";

import { useEffect, useRef, useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { IconClose, IconPlus } from "@/components/icons";
import { compressPinPhoto } from "@/lib/image/compressImage";
import PinPhotoCropper from "./PinPhotoCropper";

interface PinPhotoUploaderProps {
  /** 이미 업로드된 사진 원본 URL (없으면 미선택 상태). */
  photoUrl?: string | null;
  /** 이미 업로드된 썸네일 URL — 미리보기 우선 사용(가벼움). */
  thumbnailUrl?: string | null;
  /** 압축 완료된 File 을 전달한다. 실제 업로드 API 호출은 호출처 책임. */
  onFileSelected: (file: File) => void | Promise<void>;
  /** 기존 사진 삭제 콜백. 없으면 삭제 버튼 미노출. */
  onDelete?: () => void | Promise<void>;
  /** 업로드 진행 중 — 진행 표시 + 입력 비활성. */
  uploading?: boolean;
  /** 외부 비활성(저장 중 등). */
  disabled?: boolean;
}

/**
 * Phase 13 (FR-PIN-9g~k): 추억핀 사진 선택/미리보기 공용 컴포넌트.
 *
 * 단일 책임 — `<input type="file" accept="image/*">`(모바일 카메라 직캡처 허용)로 선택한
 * 이미지를 `compressPinPhoto` 로 압축한 뒤 object URL 미리보기를 띄우고 `onFileSelected` 로
 * 압축 파일을 전달한다. 실제 업로드/삭제 API 호출은 호출처(MapClient/MemoTagPanel)가 담당한다.
 *
 * 기존 사진(photoUrl/thumbnailUrl)이 있으면 현재 사진을 미리보기로 깔고 삭제 버튼을 노출한다.
 * 로컬에서 새로 선택한 미리보기가 있으면 그것을 우선 표시한다.
 */
export default function PinPhotoUploader({
  photoUrl,
  thumbnailUrl,
  onFileSelected,
  onDelete,
  uploading = false,
  disabled = false,
}: PinPhotoUploaderProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [localPreview, setLocalPreview] = useState<string | null>(null);
  const [compressing, setCompressing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 작업 1: 파일 선택 직후 1:1 크롭 모달에 넘길 원본 파일. null 이면 모달 닫힘.
  const [cropFile, setCropFile] = useState<File | null>(null);

  // object URL 정리 (메모리 누수 방지).
  useEffect(() => {
    return () => {
      if (localPreview) URL.revokeObjectURL(localPreview);
    };
  }, [localPreview]);

  const busy = uploading || compressing || disabled;
  // 표시할 미리보기: 로컬 선택본 우선, 그다음 기존 썸네일 → 원본.
  const previewUrl = localPreview ?? thumbnailUrl ?? photoUrl ?? null;
  const hasExisting = Boolean(photoUrl ?? thumbnailUrl);

  const handlePick = () => {
    if (busy) return;
    inputRef.current?.click();
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // 같은 파일 재선택도 onChange 가 발화하도록 input 값을 비운다.
    e.target.value = "";
    if (!file) return;
    // 작업 1: 곧장 compress 하지 않고 1:1 크롭 모달을 연다.
    setError(null);
    setCropFile(file);
  };

  // 크롭 확인: 정사각 결과를 compress → 미리보기 + onFileSelected.
  const handleCropConfirm = async (cropped: File) => {
    setCropFile(null);
    setCompressing(true);
    try {
      const compressed = await compressPinPhoto(cropped);
      const url = URL.createObjectURL(compressed);
      setLocalPreview((prev) => {
        if (prev) URL.revokeObjectURL(prev);
        return url;
      });
      await onFileSelected(compressed);
    } catch {
      setError("사진을 처리하지 못했어요. 다른 사진으로 다시 시도해 주세요.");
    } finally {
      setCompressing(false);
    }
  };

  // 크롭 취소: 모달 닫고 파일 초기화 (재선택 가능).
  const handleCropCancel = () => {
    setCropFile(null);
  };

  const handleDelete = async () => {
    if (busy || !onDelete) return;
    setError(null);
    // 로컬 미리보기도 함께 해제 (삭제 후 빈 슬롯으로 복귀).
    setLocalPreview((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return null;
    });
    await onDelete();
  };

  return (
    <div style={{ fontFamily: fonts.sans }}>
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        onChange={handleChange}
        disabled={busy}
        style={{ display: "none" }}
      />

      {previewUrl ? (
        <div
          style={{
            position: "relative",
            width: "100%",
            borderRadius: 12,
            overflow: "hidden",
            border: `1px solid ${colors.hairline}`,
            background: colors.bg,
          }}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={previewUrl}
            alt="추억 사진 미리보기"
            style={{
              display: "block",
              width: "100%",
              maxHeight: 200,
              objectFit: "cover",
              opacity: busy ? 0.6 : 1,
              transition: "opacity 0.2s ease",
            }}
          />

          {uploading ? (
            <div
              style={{
                position: "absolute",
                inset: 0,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                background: "rgba(26,26,46,0.28)",
                color: colors.panel,
                fontSize: 13,
                fontWeight: 600,
              }}
            >
              업로드 중...
            </div>
          ) : null}

          {/* 단일 사진 — 삭제(x) 버튼만 노출. 교체는 삭제 후 다시 추가한다.
              (여러 장 추가처럼 보이던 + 버튼은 제거.) */}
          {(hasExisting || localPreview) && onDelete ? (
            <div
              style={{
                position: "absolute",
                top: 8,
                right: 8,
              }}
            >
              <button
                type="button"
                onClick={handleDelete}
                disabled={busy}
                aria-label="사진 삭제"
                style={iconButtonStyle(busy)}
              >
                <IconClose size={16} color={colors.pinNew} />
              </button>
            </div>
          ) : null}
        </div>
      ) : (
        <button
          type="button"
          onClick={handlePick}
          disabled={busy}
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            gap: 8,
            width: "100%",
            padding: "16px 0",
            borderRadius: 12,
            border: `1.5px dashed ${colors.hairline}`,
            background: colors.bg,
            color: colors.inkSoft,
            fontFamily: fonts.sans,
            fontSize: 13,
            fontWeight: 600,
            cursor: busy ? "default" : "pointer",
          }}
        >
          {compressing ? (
            "사진 준비 중..."
          ) : (
            <>
              <IconPlus size={18} color={colors.inkSoft} />
              <span>사진 추가</span>
            </>
          )}
        </button>
      )}

      {error ? (
        <div
          role="alert"
          style={{
            marginTop: 8,
            fontSize: 12,
            color: colors.pinNew,
            fontFamily: fonts.sans,
          }}
        >
          {error}
        </div>
      ) : null}

      {/* 작업 1: 파일 선택 직후 1:1 크롭 모달. */}
      {cropFile ? (
        <PinPhotoCropper
          file={cropFile}
          onConfirm={handleCropConfirm}
          onCancel={handleCropCancel}
        />
      ) : null}
    </div>
  );
}

function iconButtonStyle(disabled: boolean): React.CSSProperties {
  return {
    width: 30,
    height: 30,
    borderRadius: "50%",
    border: "none",
    background: colors.panel,
    boxShadow: `0 2px 6px ${colors.shadowMd}`,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    padding: 0,
    cursor: disabled ? "default" : "pointer",
  };
}
