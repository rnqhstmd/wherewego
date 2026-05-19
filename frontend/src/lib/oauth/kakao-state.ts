/**
 * 카카오 OAuth2 CSRF 방지용 state 생성/검증.
 * sessionStorage 기반 1회용 토큰.
 */

const SESSION_KEY = "maygo:kakao-oauth-state";

function generateState(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  }
  // 최종 폴백 (구형 환경) — 보안 약화 인지. crypto API 미지원 환경에서만 도달.
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

export const kakaoState = {
  generate(): string {
    if (typeof window === "undefined") return "";
    const state = generateState();
    window.sessionStorage.setItem(SESSION_KEY, state);
    return state;
  },
  validate(received: string | null | undefined): boolean {
    if (typeof window === "undefined") return false;
    if (!received) return false;
    const stored = window.sessionStorage.getItem(SESSION_KEY);
    // 1회용 토큰: 검증 시점에 즉시 제거
    window.sessionStorage.removeItem(SESSION_KEY);
    return stored !== null && stored === received;
  },
};
