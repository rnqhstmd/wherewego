package com.wherewego.domain.notification;

import com.wherewego.domain.pin.want.WishConvertedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 12: {@link WishConvertedEvent} AFTER_COMMIT 리스너.
 *
 * <p>{@code WantService} 트랜잭션이 정상 커밋된 후에만 알림 fan-out 을 시작하여,
 * REEL → WISH 전환이 롤백되는 경우 알림이 잘못 발송되는 것을 차단한다 (NFR-12-3, NFR-12-4).
 * 알림 fan-out 실패는 본 발화 자체를 차단하지 않도록 try/catch 로 best-effort 격리한다 (BR-3).</p>
 *
 * <p>호출 계약: {@link NotificationService#createForWishConverted} 는 자체 REQUIRED 트랜잭션으로
 * 동작하며, 부분 UNIQUE 인덱스 {@code uq_notifications_wish_converted} 가 동일 핀에 대한
 * 중복 알림을 race-free 차단한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WishConvertedNotificationListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWishConverted(WishConvertedEvent event) {
        try {
            notificationService.createForWishConverted(
                    event.groupId(), event.pinId(), event.triggerUserId(), event.placeName());
        } catch (RuntimeException e) {
            // BR-3: 알림 실패는 본 발화(WANT 토글 트랜잭션)에 영향을 주지 않도록 격리.
            log.warn("WISH_CONVERTED notification failed groupId={} pinId={} triggerUserId={}",
                    event.groupId(), event.pinId(), event.triggerUserId(), e);
        }
    }
}
