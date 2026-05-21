/**
 * 알림 도메인 타입. 백엔드 `NotificationV1Dto` / SSE payload와 1:1 대응.
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
 * SSE `notification` 이벤트의 payload. 목록 항목보다 가벼운 형태로 푸시된다.
 */
export interface NotificationStreamEvent {
  id: number;
  type: NotificationType;
  registeredByNickname: string;
  firstPlaceName: string;
  totalPinCount: number;
  createdAt: string;
}

/**
 * SSE EventSource 연결 상태.
 */
export type ConnectionState = "connecting" | "open" | "closed" | "failed";
