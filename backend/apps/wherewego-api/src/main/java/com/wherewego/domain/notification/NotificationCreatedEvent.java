package com.wherewego.domain.notification;

import java.time.Instant;

/**
 * 알림 생성 도메인 이벤트.
 * NotificationService.createForXxx 트랜잭션 커밋 후
 * NotificationSsePushListener가 @TransactionalEventListener(AFTER_COMMIT)으로 수신하여 SSE push.
 */
public record NotificationCreatedEvent(
        Long receiverId,
        Long notificationId,
        NotificationType type,
        Long registeredBy,
        String registeredByNickname,
        String firstPlaceName,
        int totalPinCount,
        Instant createdAt
) {
}
