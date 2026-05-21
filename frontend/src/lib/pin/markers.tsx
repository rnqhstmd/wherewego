/**
 * Pin marker glyph 공통 모듈 — Phase 7 태그 3종(REEL/WISH/MEMORY) 리뉴얼.
 *
 * PinDot.tsx(React)와 MapboxView.renderPinDotInto(vanilla DOM)가 동일한 SVG
 * 모양을 사용하도록 단일 소스로 추출. 칩(PinTag.tsx)도 동일 글리프 재사용.
 *
 * - vanilla string 함수: 같은 markup을 template literal로 반환 (DOM innerHTML 삽입용)
 * - React 컴포넌트: 같은 markup을 JSX로 반환
 * - 색상 기본값은 Tailwind v4 @theme CSS 변수 참조 (`var(--color-pin-*)`).
 *   인자로 색상을 받으면 그 값을 우선 사용.
 */

import type { JSX } from "react";

export type PinKind = "reel" | "wish" | "memory";

/**
 * 핀 종류별 기본 색상 — Tailwind v4 @theme 토큰 참조.
 * globals.css의 `--color-pin-reel/wish/memory`와 동기화.
 */
export const PIN_COLORS: Record<PinKind, string> = {
  reel: "var(--color-pin-reel)",
  wish: "var(--color-pin-wish)",
  memory: "var(--color-pin-memory)",
};

// 16진수 80 = ~50% alpha. CSS 변수와도 호환되도록 color8 형태로 합성.
function shadowFilter(color: string): string {
  return `drop-shadow(0 1px 3px ${color}80)`;
}

// =====================================================================
// vanilla DOM 삽입용 SVG string
// =====================================================================

/**
 * REEL 글리프 — 인스타그램 스타일 (둥근 정사각형 외곽 + 중앙 렌즈 + 우상단 점).
 * viewBox: 0 0 24 24. size param은 가로/세로 동시 적용.
 */
export function getReelSvgString(size: number, color?: string): string {
  const c = color ?? PIN_COLORS.reel;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 24 24" aria-hidden="true" data-testid="pin-glyph-reel" style="filter:${shadowFilter(c)};flex-shrink:0;"><rect x="3" y="3" width="18" height="18" rx="5" ry="5" fill="none" stroke="${c}" stroke-width="2.2"/><circle cx="12" cy="12" r="4" fill="none" stroke="${c}" stroke-width="2.2"/><circle cx="17.5" cy="6.5" r="1.2" fill="${c}"/></svg>`;
}

/**
 * WISH 글리프 — 채워진 단순 원 (기존 PLACE 마커 형태 재사용).
 * viewBox: 0 0 10 10. size param은 가로/세로 동시 적용.
 */
export function getWishSvgString(size: number, color?: string): string {
  const c = color ?? PIN_COLORS.wish;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 10 10" aria-hidden="true" data-testid="pin-glyph-wish" style="filter:${shadowFilter(c)};flex-shrink:0;"><circle cx="5" cy="5" r="5" fill="${c}"/></svg>`;
}

/**
 * MEMORY 글리프 — 하트 (기존 PinDot MEMORY path 재사용).
 * viewBox: -8 -6 16 12. w/h 별도 지정 (PinDot 기본 비율 1.5:1.3).
 */
export function getMemorySvgString(w: number, h: number, color?: string): string {
  const c = color ?? PIN_COLORS.memory;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="-8 -6 16 12" aria-hidden="true" data-testid="pin-glyph-memory" style="filter:${shadowFilter(c)};flex-shrink:0;"><path d="M 0 4.5 C -7 0 -8 -5 -3.5 -5 C -1.5 -5 0 -3 0 -3 C 0 -3 1.5 -5 3.5 -5 C 8 -5 7 0 0 4.5 Z" fill="${c}"/></svg>`;
}

// =====================================================================
// React 컴포넌트 (string 버전과 시각적으로 100% 동일)
// =====================================================================

export function ReelGlyph({
  size,
  color,
}: {
  size: number;
  color?: string;
}): JSX.Element {
  const c = color ?? PIN_COLORS.reel;
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden="true"
      data-testid="pin-glyph-reel"
      style={{ filter: shadowFilter(c), flexShrink: 0 }}
    >
      <rect
        x="3"
        y="3"
        width="18"
        height="18"
        rx="5"
        ry="5"
        fill="none"
        stroke={c}
        strokeWidth="2.2"
      />
      <circle cx="12" cy="12" r="4" fill="none" stroke={c} strokeWidth="2.2" />
      <circle cx="17.5" cy="6.5" r="1.2" fill={c} />
    </svg>
  );
}

export function WishGlyph({
  size,
  color,
}: {
  size: number;
  color?: string;
}): JSX.Element {
  const c = color ?? PIN_COLORS.wish;
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      viewBox="0 0 10 10"
      aria-hidden="true"
      data-testid="pin-glyph-wish"
      style={{ filter: shadowFilter(c), flexShrink: 0 }}
    >
      <circle cx="5" cy="5" r="5" fill={c} />
    </svg>
  );
}

export function MemoryGlyph({
  w,
  h,
  color,
}: {
  w: number;
  h: number;
  color?: string;
}): JSX.Element {
  const c = color ?? PIN_COLORS.memory;
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={w}
      height={h}
      viewBox="-8 -6 16 12"
      aria-hidden="true"
      data-testid="pin-glyph-memory"
      style={{ filter: shadowFilter(c), flexShrink: 0 }}
    >
      <path
        d="M 0 4.5 C -7 0 -8 -5 -3.5 -5 C -1.5 -5 0 -3 0 -3 C 0 -3 1.5 -5 3.5 -5 C 8 -5 7 0 0 4.5 Z"
        fill={c}
      />
    </svg>
  );
}
