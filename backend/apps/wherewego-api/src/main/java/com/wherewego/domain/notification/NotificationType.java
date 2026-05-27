package com.wherewego.domain.notification;

public enum NotificationType {
    MANUAL_PIN,
    CHATBOT_PINS,
    VISIT_DETECTED,
    /**
     * Phase 12: REEL 핀이 그룹원 과반의 WANT 로 WISH 로 자동 전환되었음을 알리는 타입.
     * 본 행은 {@code notifications.wish_pin_id} 컬럼이 non-null 로 채워지며, 부분 UNIQUE
     * {@code uq_notifications_wish_converted} 가 동일 핀에 대한 중복 알림을 race-free 차단한다.
     */
    WISH_CONVERTED
}
