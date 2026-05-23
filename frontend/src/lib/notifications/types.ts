/**
 * 알림 도메인 타입. 백엔드 `NotificationV1Dto`와 1:1 대응.
 *
 * <p>날짜 필드는 ISO 8601 문자열(서버가 그대로 직렬화)이며,
 * BigDecimal 좌표는 정밀도 손실 방지를 위해 문자열로 직렬화된다.</p>
 */

export type NotificationType = "MANUAL_PIN" | "CHATBOT_PINS";

export interface NotificationItem {
  id: number;
  type: NotificationType;
  registeredBy: number | null;
  registeredByNickname: string;
  firstPlaceName: string;
  totalPinCount: number;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationListResponse {
  items: NotificationItem[];
  unreadCount: number;
}

export interface NotificationPinItem {
  pinId: number;
  placeName: string;
  address: string | null;
  latitude: string | null;
  longitude: string | null;
  deleted: boolean;
  instagramUrl: string | null;
}

export interface NotificationDetail {
  id: number;
  type: NotificationType;
  registeredByNickname: string;
  createdAt: string;
  pins: NotificationPinItem[];
}

export interface ReadAllResponse {
  updatedCount: number;
}

/**
 * 토스트 노출용 알림 요약. mount/visibility/focus 트리거 fetch 결과에서
 * 직전 max id를 초과한 최상위 신규 알림 1건을 변환하여 사용한다 (옵션 B, 2026-05-21).
 */
export interface NotificationToastPayload {
  id: number;
  type: NotificationType;
  registeredByNickname: string;
  firstPlaceName: string;
  totalPinCount: number;
  createdAt: string;
}
