// Design tokens for Phase 6 — 1:1 port of design-bundle/project/tokens.jsx (T, F)
// 컬러/폰트 토큰. globals.css의 @theme 변수와 짝을 이룬다.

export const colors = {
  bg: "#FAF8F5",
  panel: "#FFFFFF",
  mapBg: "#EAE4D4",
  mapWater: "#D4E8F0",
  mapPark: "#D5E5CB",
  mapBlock: "#F0EBE0",
  mapRoad: "#FFFFFF",
  pinPlace: "#7BB3E8",
  pinMemory: "#F4A8B0",
  pinNew: "#E05A5A",
  cta: "#C4622D",
  ctaHover: "#A84E23",
  ctaSub: "#8B8B9E",
  kakao: "#FEE500",
  kakaoInk: "#191600",
  ink: "#1A1A2E",
  inkSoft: "#8B8B9E",
  inkFaint: "#C5C5D0",
  hairline: "#E8E4DE",
  shadow: "rgba(26,26,46,0.08)",
  shadowMd: "rgba(26,26,46,0.13)",
} as const;

export const fonts = {
  serif: "var(--font-serif)", // Noto Serif KR
  emo: "var(--font-emo)", // Gowun Batang
  sans: "var(--font-sans)", // Pretendard (CDN)
  mono: "var(--font-mono)", // JetBrains Mono
} as const;

export type ColorToken = keyof typeof colors;
export type FontToken = keyof typeof fonts;
