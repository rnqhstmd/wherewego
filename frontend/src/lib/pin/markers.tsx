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
import type { PinTag } from "@/lib/api/types";

export type PinKind = "reel" | "interest" | "wish" | "memory";

/**
 * 핀 종류별 기본 색상 — context/tag/README.md 정의대로 hex로 직접 박는다.
 *
 * SVG의 `fill` 속성은 CSS 변수(`var(--color-pin-*)`)를 일관되게 인식하지 못해
 * 브라우저가 default(검정)로 폴백되는 회귀가 있었다. globals.css의 `--color-pin-*`
 * 토큰과 동일한 hex를 직접 사용해 모든 환경에서 정확한 파스텔 톤이 노출되도록 한다.
 *
 * - REEL: 하늘색 #7BB3E8 동그라미 (인스타 릴스에서 발견한 곳)
 * - INTEREST (Phase 12 D-13): 진보라 #7B68EE 동그라미 (REEL + want_count>=1)
 * - WISH: 노랑 #F4C842 별모양 (가보고 싶은 곳)
 * - MEMORY: 파스텔 핑크 #FFB3C6 하트 (다녀온 곳)
 */
export const PIN_COLORS: Record<PinKind, string> = {
  reel: "#7BB3E8",     // 하늘색 동그라미 — 인스타 발견의 부드러운 톤
  interest: "#7B68EE", // 진보라 동그라미 — REEL with want_count>=1 (Phase 12)
  wish: "#F4C842",     // 진한 파스텔 노랑 (머스타드 hint) — 흰 지도 배경에서 별이 또렷
  memory: "#FFB3C6",   // 파스텔 핑크 하트 — 그대로
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
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 10 10" aria-hidden="true" data-testid="pin-glyph-reel" style="filter:${shadowFilter(c)};flex-shrink:0;"><circle cx="5" cy="5" r="4" fill="${c}"/></svg>`;
}

/**
 * INTEREST 글리프 (Phase 12) — REEL과 동일한 원 모양, 색만 진보라(#7B68EE).
 * 실제 마커 전환은 색·크기 transition이고 모양 변경은 없다(설계 §3 참조).
 * viewBox: 0 0 10 10. size param은 가로/세로 동시 적용.
 */
export function getInterestSvgString(size: number, color?: string): string {
  const c = color ?? PIN_COLORS.interest;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 10 10" aria-hidden="true" data-testid="pin-glyph-interest" style="filter:${shadowFilter(c)};flex-shrink:0;"><circle cx="5" cy="5" r="4" fill="${c}"/></svg>`;
}

/**
 * WISH 글리프 — 채워진 단순 원 (기존 PLACE 마커 형태 재사용).
 * viewBox: 0 0 10 10. size param은 가로/세로 동시 적용.
 */
export function getWishSvgString(size: number, color?: string): string {
  const c = color ?? PIN_COLORS.wish;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 10 10" aria-hidden="true" data-testid="pin-glyph-wish" style="filter:${shadowFilter(c)};flex-shrink:0;"><path d="M 5 0.1 L 6.18 3.38 L 9.66 3.49 L 6.90 5.62 L 7.88 8.96 L 5 7.0 L 2.12 8.96 L 3.10 5.62 L 0.34 3.49 L 3.82 3.38 Z" fill="${c}"/></svg>`;
}

/**
 * MEMORY 글리프 — 하트 (기존 PinDot MEMORY path 재사용).
 * viewBox: -8 -6 16 12. w/h 별도 지정 (PinDot 기본 비율 1.5:1.3).
 */
export function getMemorySvgString(w: number, h: number, color?: string): string {
  const c = color ?? PIN_COLORS.memory;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 24 24" aria-hidden="true" data-testid="pin-glyph-memory" style="filter:${shadowFilter(c)};flex-shrink:0;"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41 0.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z" fill="${c}"/></svg>`;
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
      viewBox="0 0 10 10"
      aria-hidden="true"
      data-testid="pin-glyph-reel"
      style={{ filter: shadowFilter(c), flexShrink: 0 }}
    >
      <circle cx="5" cy="5" r="4" fill={c} />
    </svg>
  );
}

export function InterestGlyph({
  size,
  color,
}: {
  size: number;
  color?: string;
}): JSX.Element {
  const c = color ?? PIN_COLORS.interest;
  // Phase 12 — REEL과 동일한 원 모양, 색만 진보라(#7B68EE).
  // 마커 전환은 색·크기 transition이고 모양 변경은 없음(설계 §3).
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      viewBox="0 0 10 10"
      aria-hidden="true"
      data-testid="pin-glyph-interest"
      style={{ filter: shadowFilter(c), flexShrink: 0 }}
    >
      <circle cx="5" cy="5" r="4" fill={c} />
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
  // 5각 별 — 외부 반지름 4.6, 내부 반지름 1.9, 중심 (5,5).
  // 위시리스트의 "반짝임" 의미 + 노랑 파스텔과 합성하여 따뜻한 톤.
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
      <path
        d="M 5 0.1 L 6.18 3.38 L 9.66 3.49 L 6.90 5.62 L 7.88 8.96 L 5 7.0 L 2.12 8.96 L 3.10 5.62 L 0.34 3.49 L 3.82 3.38 Z"
        fill={c}
      />
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
  // main의 material standard 하트 SVG (viewBox 0 0 24 24 정사각). 정사각 호출에서 종횡비 깨지지 않음.
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={w}
      height={h}
      viewBox="0 0 24 24"
      aria-hidden="true"
      data-testid="pin-glyph-memory"
      style={{ filter: shadowFilter(c), flexShrink: 0 }}
    >
      <path
        d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41 0.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
        fill={c}
      />
    </svg>
  );
}

// =====================================================================
// Phase 12 — 마커 variant 결정 헬퍼 (단일 진입점)
// =====================================================================

/**
 * 마커 시각화 결정 결과 (설계 §9.1).
 *
 * - kind: 글리프 종류 (color + 모양 — wish/memory만 모양이 다름)
 * - size: 베이스 사이즈에 곱할 계수
 *   · REEL = 1.0 (기본)
 *   · INTEREST = 1.1 (강조)
 *   · WISH = 1.2 (가장 큼)
 *   · MEMORY = 1.0
 * - pulse: 본 헬퍼는 항상 false를 반환. 펄스는 "REEL → WISH 즉시 전환 시점"에만 1회
 *   트리거되는 일회성 효과이며, MapClient/PinDot에서 별도 prop으로 전달된다.
 */
export interface MarkerVariant {
  kind: PinKind;
  size: number;
  pulse: boolean;
}

/**
 * (tag, wantCount) → MarkerVariant 결정 (단일 진입점).
 * PinDot/MapboxView가 모두 이 헬퍼를 통해 색·크기를 일관되게 결정한다.
 *
 *  - tag=MEMORY                       → memory, 1.0
 *  - tag=WISH                         → wish, 1.2
 *  - tag=REEL && wantCount >= 1       → interest, 1.1  (Phase 12 D-13)
 *  - tag=REEL && wantCount == 0       → reel, 1.0
 */
export function getMarkerVariant(tag: PinTag, wantCount: number): MarkerVariant {
  if (tag === "MEMORY") return { kind: "memory", size: 1.0, pulse: false };
  if (tag === "WISH") return { kind: "wish", size: 1.2, pulse: false };
  if (wantCount >= 1) return { kind: "interest", size: 1.1, pulse: false };
  return { kind: "reel", size: 1.0, pulse: false };
}
