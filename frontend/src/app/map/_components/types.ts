/**
 * 핀 추가 흐름에서 검색/Crosshair 진입을 모두 표현하는 공통 origin.
 *
 * - 검색 → 선택: placeName/address/좌표가 결과 그대로, editable=false.
 * - Crosshair → 완료: placeName="" + editable=true → MemoTagPanelContent에서 사용자 입력 받음.
 */
export interface NewPinOrigin {
  placeName: string;
  address: string | null;
  latitude: number;
  longitude: number;
  editable: boolean;
}

/** ActionBar/DesktopSidebar가 공유하는 활성 탭 상태. */
export type ActionBarTab = "search" | "add" | "roulette" | null;
