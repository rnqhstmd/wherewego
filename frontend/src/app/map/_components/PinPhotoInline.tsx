"use client";

import { useState } from "react";
import { colors } from "@/lib/design/tokens";

interface PinPhotoInlineProps {
  /** 캐시된 썸네일 URL — blur-up placeholder 로 즉시 깔린다(추가 GET 0). */
  thumbnailUrl: string;
  /** 원본 사진 URL — 로드 완료 시 opacity 전환으로 선명하게 드러난다. */
  photoUrl: string;
  /** 메모로 복귀 콜백 (사진 영역 탭). */
  onBack: () => void;
}

/**
 * 추억핀 사진 — 말풍선 안 제자리(인라인) 사진 노드.
 *
 * 말풍선 메모 영역을 대체하여 펼쳐지는 1:1 정사각 사진. 캐시된 `thumbnailUrl` 을
 * `filter: blur(12px)` placeholder 로 즉시 깔고, 원본 `<img onLoad>` 가 끝나면 opacity 로
 * 전환한다(스피너 없음). 사진 영역 어디든 탭하면 메모로 복귀한다(별도 버튼 없음).
 * (Phase 13 PinPhotoViewer 전체화면 뷰어를 대체하는 제자리 전환 컴포넌트.)
 */
export default function PinPhotoInline({
  thumbnailUrl,
  photoUrl,
  onBack,
}: PinPhotoInlineProps) {
  const [loaded, setLoaded] = useState(false);

  return (
    <div
      onClick={onBack}
      style={{
        position: "relative",
        width: "100%",
        aspectRatio: "1 / 1",
        borderRadius: 12,
        overflow: "hidden",
        background: colors.ink,
        cursor: "pointer",
      }}
    >
      {/* blur-up placeholder — 썸네일을 흐리게 깔고 위에 원본을 겹친다. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={thumbnailUrl}
        alt=""
        aria-hidden="true"
        style={{
          position: "absolute",
          inset: 0,
          width: "100%",
          height: "100%",
          objectFit: "cover",
          filter: "blur(12px)",
          transform: "scale(1.08)",
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
          width: "100%",
          height: "100%",
          objectFit: "cover",
          opacity: loaded ? 1 : 0,
          transition: "opacity 0.4s ease",
        }}
      />
    </div>
  );
}
