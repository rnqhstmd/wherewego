/**
 * Gate(2인 비공개 서비스) 쿠키 발급/검증.
 *
 * 단일 초대 코드(GATE_INVITE_CODE) 입력 방식.
 * 쿠키 값 = HMAC-SHA256(CODE, GATE_COOKIE_SECRET) 의 hex.
 * 서버만 secret을 알기 때문에 외부에서 위조 불가.
 * Web Crypto API 사용 (Edge runtime 호환 — middleware에서 호출 가능).
 */

export const GATE_COOKIE_NAME = "maygo-gate";
export const GATE_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30; // 30일

const encoder = new TextEncoder();

async function hmacHex(key: string, data: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    encoder.encode(key),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(data));
  return Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** 환경변수에서 초대 코드 + secret을 읽어 expected 쿠키 값을 계산. */
export async function computeExpectedGateCookie(): Promise<string | null> {
  const code = process.env.GATE_INVITE_CODE;
  const secret = process.env.GATE_COOKIE_SECRET;
  if (!code || !secret) return null;
  return hmacHex(secret, code);
}

export async function verifyGateCookie(
  value: string | undefined,
): Promise<boolean> {
  if (!value) return false;
  const expected = await computeExpectedGateCookie();
  if (!expected) return false;
  return timingSafeEqual(value, expected);
}

export async function verifyInviteCode(code: string): Promise<boolean> {
  const envCode = process.env.GATE_INVITE_CODE;
  if (!envCode) return false;
  return timingSafeEqual(code, envCode);
}
