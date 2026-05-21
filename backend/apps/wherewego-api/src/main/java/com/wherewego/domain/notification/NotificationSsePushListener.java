package com.wherewego.domain.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * NotificationCreatedEvent를 NotificationService 트랜잭션 커밋 후 수신하여 SSE push.
 *
 * AFTER_COMMIT의 "COMMIT"은 NotificationService 내부 @Transactional의 커밋 기준이지,
 * 호출자(PinV1Controller, 챗봇 핸들러)의 트랜잭션이 아니다.
 * 호출자는 트랜잭션 밖에서 NotificationService를 호출한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationSsePushListener {

    private final NotificationSseRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(NotificationCreatedEvent event) {
        registry.push(event.receiverId(), event);
    }
}
