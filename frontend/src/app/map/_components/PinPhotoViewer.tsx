"use client";

import { useEffect, useRef, useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { IconClose } from "@/components/icons";

interface PinPhotoViewerProps {
  /** 캐시된 썸네일 URL — blur-up placeholder 로 즉시 깔린다(추가 GET 0). */
  thumbnailUrl: string;
  /** 원본 사진 URL — 로드 완료 시 opacity 전환으로 선명하게 드러난다. */
  photoUrl: string;
  /** 닫기 콜백 (backdrop / X / swipe down). */
  onClose: () => void;
}

const SWIPE_CLOSE_THRESHOLD = 80;

/**
 * Phase 13 (FR-PIN-11b, QE-3): 추억핀 원본 사진 뷰어.
 *
 * 마운트 시 우측에서 슬라이드 인(CSS transform/transition)한다. 썸네일을 `filter: blur(12px)`
 * 배경 placeholder 로 즉시 깔고, 원본 `<img onLoad>` 가 끝나면 opacity 로 전환한다(스피너 없음).
 * 닫기: backdrop 클릭 / X 버튼 / swipe down(세로 드래그). 썸네일은 말풍선이 이미 띄운 캐시를
 * 재사용하므로 추가 GET 이 발생하지 않는다.
 */
export default function PinPhotoViewer({
  thumbnailUrl,
  photoUrl,
  onClose,
}: PinPhotoViewerProps) {
  const [loaded, setLoaded] = useState(false);
  const [entered, setEntered] = useState(false);
  const touchStartY = useRef<number | null>(null);
  const [dragY, setDragY] = useState(0);

  // 마운트 직후 한 프레임 뒤 enter → 슬라이드 인 transition 발화.
  useEffect(() => {
    const id = requestAnimationFrame(() => setEntered(true));
    return () => cancelAnimationFrame(id);
  }, []);

  // ESC 로도 닫기.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartY.current = e.touches[0].clientY;
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    if (touchStartY.current === null) return;
    const delta = e.touches[0].clientY - touchStartY.current;
    // 아래 방향 드래그만 추적.
    setDragY(delta > 0 ? delta : 0);
  };

  const handleTouchEnd = () => {
    if (dragY > SWIPE_CLOSE_THRESHOLD) {
      onClose();
    } else {
      setDragY(0);
    }
    touchStartY.current = null;
  };

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 60,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: `rgba(26,26,46,${entered ? 0.82 : 0})`,
        transition: "background 0.28s ease",
        fontFamily: fonts.sans,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        style={{
          position: "relative",
          maxWidth: "92vw",
          maxHeight: "86vh",
          transform: entered
            ? `translate(0, ${dragY}px)`
            : "translateX(36px)",
          opacity: entered ? 1 : 0,
          transition: touchStartY.current
            ? "none"
            : "transform 0.28s ease, opacity 0.28s ease",
        }}
      >
        {/* blur-up placeholder — 썸네일을 흐리게 깔고 위에 원본을 겹친다. */}
        <div
          style={{
            position: "relative",
            borderRadius: 14,
            overflow: "hidden",
            boxShadow: `0 18px 48px ${colors.shadowMd}`,
            background: colors.ink,
          }}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={thumbnailUrl}
            alt=""
            aria-hidden="true"
            style={{
              display: "block",
              maxWidth: "92vw",
              maxHeight: "86vh",
              objectFit: "contain",
              filter: "blur(12px)",
              transform: "scale(1.05)",
              opacity: loaded ? 0 : 1,
              transition: "opacity 0.4s ease",
            }}
          />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={photoUrl}
            alt="추억 사진"
            onLoad={() => setLoaded(true)}
            style={{
              position: "absolute",
              inset: 0,
              display: "block",
              width: "100%",
              height: "100%",
              objectFit: "contain",
              opacity: loaded ? 1 : 0,
              transition: "opacity 0.4s ease",
            }}
          />
        </div>

        <button
          type="button"
          onClick={onClose}
          aria-label="닫기"
          style={{
            position: "absolute",
            top: 8,
            right: 8,
            width: 36,
            height: 36,
            borderRadius: "50%",
            border: "none",
            background: "rgba(26,26,46,0.55)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            padding: 0,
            cursor: "pointer",
          }}
        >
          <IconClose size={20} color={colors.panel} />
        </button>
      </div>
    </div>
  );
}
