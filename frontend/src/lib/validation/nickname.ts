/**
 * 닉네임 유효성 검증.
 * 한글/영문/숫자만 허용, 길이 2~12자.
 */

export type NicknameValidationResult =
  | { valid: true }
  | { valid: false; reason: "too_short" | "too_long" | "invalid_char" };

const PATTERN = /^[가-힣a-zA-Z0-9]+$/;

export function validateNickname(value: string): NicknameValidationResult {
  if (value.length === 0) return { valid: false, reason: "too_short" };
  if (value.length < 2) return { valid: false, reason: "too_short" };
  if (value.length > 12) return { valid: false, reason: "too_long" };
  if (!PATTERN.test(value)) return { valid: false, reason: "invalid_char" };
  return { valid: true };
}

/**
 * 입력값에서 한글/영문/숫자 외 문자를 제거하고 12자로 절단한다.
 */
export function sanitizeNickname(value: string): string {
  return value.replace(/[^가-힣a-zA-Z0-9]/g, "").slice(0, 12);
}
