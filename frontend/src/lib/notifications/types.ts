/**
 * 알림 도메인 타입. 백엔드 `NotificationV1Dto`와 1:1 대응.
 *
 * <p>날짜 필드는 ISO 8601 문자열(서버가 그대로 직렬화)이며,
 * BigDecimal 좌표는 정밀도 손실 방지를 위해 문자열로 직렬화된다.</p>
 */

/**
 * Phase 12 (FR-PIN-12-6): WISH_CONVERTED 알림 타입 추가.
 *
 * REEL → WISH 자동 전환(과반 WANT) 시 발사되는 알림. 백엔드
 * `NotificationType.WISH_CONVERTED` enum 과 1:1 대응. 본 타입의 알림은
 * 백엔드에서 `notifications.wish_pin_id` 컬럼으로 단일 핀을 직접 참조하며
 * (`NotificationPin` 링크 테이블 미사용, V009 visit_pin_id 패턴 답습),
 * 클라이언트 관점에서는 상세 응답의 `pins` 배열에 단일 핀으로 내려온다.
 */
export type NotificationType =
  | "MANUAL_PIN"
  | "CHATBOT_PINS"
  | "VISIT_DETECTED"
  | "WISH_CONVERTED";

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
  /**
   * Phase 10 VISIT_DETECTED: 알림 상세 조회 시 백엔드가 최신 핀 메모를 join 하여 채움.
   * MANUAL_PIN/CHATBOT_PINS 알림은 null. soft-delete 핀도 null.
   */
  memo?: string | null;
  /**
   * Phase 10 FR-VD-29: 핀의 현재 태그(REEL/WISH/MEMORY). 알림 상세에서
   * VISIT_DETECTED 케이스의 MEMORY 배지 표시에 사용. soft-delete 또는
   * 핀 자체가 사라진 경우 null.
   */
  tag?: string | null;
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
