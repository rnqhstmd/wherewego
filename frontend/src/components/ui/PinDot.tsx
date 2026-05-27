import type { CSSProperties } from "react";
import type { PinTag } from "@/lib/api/types";
import {
  InterestGlyph,
  MemoryGlyph,
  ReelGlyph,
  WishGlyph,
  getMarkerVariant,
  type PinKind,
} from "@/lib/pin/markers";

export type PinDotType = "reel" | "interest" | "wish" | "memory";

interface PinDotProps {
  /**
   * 명시적 글리프 종류. `tag` prop과 동시 사용하면 `tag`가 우선한다.
   * 기존 호출처(login/gate/onboarding/RouletteSpinContent 등)는 본 prop을 사용한다.
   */
  type?: PinDotType;
  /**
   * Phase 12 — 핀의 태그. 제공 시 `wantCount`와 함께 `getMarkerVariant`로 kind/size를 결정.
   */
  tag?: PinTag;
  /**
   * Phase 12 — WANT 카운트. `tag` prop과 함께 사용한다 (default 0).
   * REEL + wantCount>=1이면 interest variant로 자동 전환.
   */
  wantCount?: number;
  /**
   * Phase 12 — true면 .pin-pulse-once 클래스를 부착하여 1회 펄스 keyframe(0.5s) 재생.
   * REEL → WISH 즉시 전환 시점에 부모(MapClient)가 트리거한다.
   */
  pulse?: boolean;
  size?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * Map pin marker — Phase 7 태그 3종(REEL/WISH/MEMORY) 글리프 + Phase 12 INTEREST 글리프.
 *
 * `@/lib/pin/markers`의 React 글리프(ReelGlyph/InterestGlyph/WishGlyph/MemoryGlyph)에
 * 1:1로 위임한다. MapboxView의 vanilla DOM 마커(`getXxxSvgString`)와
 * 동일한 SVG markup을 공유한다.
 *
 * Phase 12 — `tag` + `wantCount` prop을 사용하면 `getMarkerVariant`로 kind/size를 결정하고
 * variant.size 계수를 size에 곱한다(REEL=1.0, INTEREST=1.1, WISH=1.2, MEMORY=1.0).
 * `pulse=true`면 .pin-pulse-once 클래스를 부착하여 1회 keyframe(0.5s) 재생.
 *
 * M1 fallback: 알 수 없는 type은 WishGlyph로 폴백.
 */
export function PinDot({
  type,
  tag,
  wantCount,
  pulse = false,
  size = 10,
  className,
  style,
}: PinDotProps) {
  // tag prop이 들어오면 getMarkerVariant로 단일 결정. 아니면 기존 type 사용.
  let kind: PinKind;
  let effectiveSize = size;
  if (tag) {
    const variant = getMarkerVariant(tag, wantCount ?? 0);
    kind = variant.kind;
    effectiveSize = size * variant.size;
  } else {
    kind = (type ?? "wish") as PinKind;
  }

  let glyph;
  switch (kind) {
    case "reel":
      glyph = <ReelGlyph size={effectiveSize} />;
      break;
    case "interest":
      glyph = <InterestGlyph size={effectiveSize} />;
      break;
    case "memory":
      glyph = <MemoryGlyph w={effectiveSize} h={effectiveSize} />;
      break;
    case "wish":
      glyph = <WishGlyph size={effectiveSize} />;
      break;
    default:
      // M1 fallback: 알 수 없는 enum → WISH 글리프
      // Phase 7 사용자 확인된 안전장치 — 운영 관찰 목적
      console.warn("[PinDot] Unknown PinDot kind, falling back to wish:", kind);
      glyph = <WishGlyph size={effectiveSize} />;
      break;
  }

  // pulse=true일 때 .pin-pulse-once 클래스 부착 (Phase 12 §9.2 펄스 keyframe).
  const pulseClass = pulse ? "pin-pulse-once" : undefined;
  const mergedClassName = [className, pulseClass].filter(Boolean).join(" ") || undefined;

  const wrapperStyle: CSSProperties | undefined =
    mergedClassName || style ? { display: "inline-flex", flexShrink: 0, ...style } : undefined;

  if (!mergedClassName && !style) {
    return glyph;
  }
  return (
    <span className={mergedClassName} style={wrapperStyle} data-tag={kind}>
      {glyph}
    </span>
  );
}
