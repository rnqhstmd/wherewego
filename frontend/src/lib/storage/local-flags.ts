/**
 * SSR-safe localStorage 플래그 접근 헬퍼.
 * - `maygo:notif-asked`: NotifPerm 안내를 1회 노출했는지 여부
 * - `maygo:nickname-set`: 닉네임 설정 완료 여부
 */

function readFlag(key: string): boolean {
  if (typeof window === "undefined") return false;
  return window.localStorage.getItem(key) === "true";
}

function setFlag(key: string, value: boolean): void {
  if (typeof window === "undefined") return;
  if (value) {
    window.localStorage.setItem(key, "true");
  } else {
    window.localStorage.removeItem(key);
  }
}

export const notifAsked = {
  get: (): boolean => readFlag("maygo:notif-asked"),
  set: (v: boolean): void => setFlag("maygo:notif-asked", v),
};

export const nicknameSet = {
  get: (): boolean => readFlag("maygo:nickname-set"),
  set: (v: boolean): void => setFlag("maygo:nickname-set", v),
};
