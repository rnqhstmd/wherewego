/**
 * 카카오 OAuth 왕복 사이의 returnUrl 보존 유틸 (EC-003).
 *
 * 흐름:
 *  - 가드(SSR)가 `/login?returnUrl=/map` 으로 리다이렉트
 *  - LoginClient 가 카카오 redirect 직전 sessionStorage 에 stash
 *  - 카카오 → /login/callback 복귀 후 consume 하여 router.replace
 *
 * 검증: 오픈 리다이렉트 방지를 위해 `/map` 또는 `/pins` 하위 경로만 허용.
 */

const SESSION_KEY = "maygo:return-url";

/**
 * 콜백에서 router.replace 로 안전하게 보낼 수 있는 경로인지 검증한다.
 * `/map`, `/pins` 의 정확한 경로 또는 하위 경로(또는 쿼리 포함)만 허용한다.
 */
export function isSafeReturnUrl(value: string | null | undefined): value is string {
  if (!value) return false;
  return /^\/(map|pins)(\/|$|\?)/.test(value);
}

export const returnUrlStash = {
  /**
   * LoginClient 의 카카오 redirect 직전 호출.
   * 안전한 returnUrl 만 sessionStorage 에 저장한다. 그 외 값은 무시.
   */
  set(value: string | null | undefined): void {
    if (typeof window === "undefined") return;
    if (!isSafeReturnUrl(value)) {
      // 안전하지 않은 값은 stash 하지 않는다 (기본 분기 사용).
      window.sessionStorage.removeItem(SESSION_KEY);
      return;
    }
    window.sessionStorage.setItem(SESSION_KEY, value);
  },
  /**
   * 콜백에서 1회 소비. 검증 통과 시 경로 반환, 그 외 null.
   * 호출 시점에 즉시 제거하여 재사용을 막는다.
   */
  consume(): string | null {
    if (typeof window === "undefined") return null;
    const stored = window.sessionStorage.getItem(SESSION_KEY);
    window.sessionStorage.removeItem(SESSION_KEY);
    return isSafeReturnUrl(stored) ? stored : null;
  },
};
