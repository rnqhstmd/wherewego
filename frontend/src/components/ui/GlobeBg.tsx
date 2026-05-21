"use client";

import type { CSSProperties } from "react";
import { useId } from "react";
import { colors } from "@/lib/design/tokens";

interface GlobeBgProps {
  w?: number | string;
  h?: number | string;
  className?: string;
  style?: CSSProperties;
}

/**
 * 로그인/스플래시 화면 배경용 일러스트 지구본 — mapbg.jsx::GlobeBg 1:1 변환.
 * radialGradient id 는 useId() 로 동적 생성하여 같은 페이지 다중 사용 시 충돌 방지.
 */
export function GlobeBg({
  w = 600,
  h = 600,
  className,
  style,
}: GlobeBgProps) {
  const uid = useId();
  const glowId = `${uid}-glow`;

  return (
    <svg
      width={w}
      height={h}
      viewBox="0 0 600 600"
      className={className}
      style={style}
      aria-hidden="true"
    >
      {/* glow */}
      <radialGradient id={glowId} cx="50%" cy="50%" r="50%">
        <stop offset="0%" stopColor={colors.mapWater} stopOpacity=".6" />
        <stop offset="60%" stopColor={colors.mapBg} stopOpacity=".3" />
        <stop offset="100%" stopColor={colors.bg} stopOpacity="0" />
      </radialGradient>
      <circle cx="300" cy="300" r="290" fill={`url(#${glowId})`} />

      {/* sphere outline */}
      <circle
        cx="300"
        cy="300"
        r="220"
        fill="#EEE8DC"
        opacity=".5"
        stroke="#D8D0C0"
        strokeWidth="1.5"
      />

      {/* equator */}
      <ellipse
        cx="300"
        cy="300"
        rx="220"
        ry="85"
        fill="none"
        stroke="#C8C0B0"
        strokeWidth="1.2"
        opacity=".6"
      />

      {/* meridians */}
      <ellipse
        cx="300"
        cy="300"
        rx="100"
        ry="220"
        fill="none"
        stroke="#C8C0B0"
        strokeWidth="1"
        opacity=".4"
      />
      <ellipse
        cx="300"
        cy="300"
        rx="185"
        ry="220"
        fill="none"
        stroke="#C8C0B0"
        strokeWidth=".8"
        opacity=".3"
      />

      {/* land blobs */}
      <path
        d="M220 220 C250 205 280 215 300 235 C320 255 345 248 358 236
               C370 224 375 236 370 250 C360 268 340 275 320 268
               C300 260 275 272 262 262 C248 252 218 240 220 220 Z"
        fill="#D8D0B8"
        opacity=".7"
      />
      <path
        d="M260 340 C278 332 298 340 308 352 C318 364 312 376 295 376
               C275 376 252 364 260 340 Z"
        fill="#D8D0B8"
        opacity=".6"
      />

      {/* pin dots on globe */}
      <circle cx="295" cy="230" r="5" fill={colors.pinMemory} opacity=".8" />
      <circle cx="335" cy="260" r="4" fill={colors.pinWish} opacity=".8" />
      <circle cx="265" cy="355" r="4" fill={colors.pinReel} opacity=".7" />
    </svg>
  );
}
