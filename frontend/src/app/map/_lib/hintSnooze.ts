/**
 * /map 의 발견성 카드 (InvitePartnerHintCard / ConnectBotHintCard) 의
 * "× 닫기" 동작을 localStorage 기반 3일 snooze 로 구현한다.
 *
 * 키 형식: `hint-card-{type}-snooze-until`
 * 값: unix epoch milliseconds 의 문자열. snooze 가 풀린 이후 자동 재노출.
 */

const SNOOZE_DURATION_MS = 3 * 24 * 60 * 60 * 1000; // 3 일

export type HintCardType = "invite-partner" | "connect-bot";

function key(type: HintCardType): string {
  return `hint-card-${type}-snooze-until`;
}

/**
 * 현재 시점에 snooze 가 걸려 있는가?
 *
 * SSR 호환을 위해 window 미존재 시 false (서버에서는 항상 노출 가능).
 * localStorage 접근 실패(사파리 시크릿 모드 등)도 false 로 처리하여
 * 사용자가 카드를 못 보는 사고는 피한다.
 */
export function isHintSnoozed(type: HintCardType): boolean {
  if (typeof window === "undefined") return false;
  try {
    const raw = window.localStorage.getItem(key(type));
    if (!raw) return false;
    const until = Number.parseInt(raw, 10);
    if (!Number.isFinite(until)) return false;
    return Date.now() < until;
  } catch {
    return false;
  }
}

/**
 * "× 닫기" 시 3일 snooze 마킹.
 */
export function snoozeHint(type: HintCardType): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(
      key(type),
      String(Date.now() + SNOOZE_DURATION_MS),
    );
  } catch {
    // 사용자가 다음 방문에서 다시 보게 되지만 동작에는 지장 없음.
  }
}
