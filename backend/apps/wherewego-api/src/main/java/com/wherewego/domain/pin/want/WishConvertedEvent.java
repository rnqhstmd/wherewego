package com.wherewego.domain.pin.want;

/**
 * Phase 12: REEL → WISH 자동 전환 이벤트.
 *
 * <p>{@link WantService} 가 토글/INSERT-only 헬퍼에서 과반 충족으로 핀의 tag 를 WISH 로 전환하면
 * 트랜잭션 내에서 본 이벤트를 발행한다. {@code WishConvertedNotificationListener} 가
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 으로 수신하여 알림을 fan-out 한다 (NFR-12-3).</p>
 *
 * <p>전달 데이터는 알림 본문 렌더링과 멱등 키 조합에 필요한 최소 식별자만 포함한다.
 * {@code placeName} 은 디버그/로그용으로만 사용하며, 실제 알림 본문 렌더링은 수신자 측에서
 * {@code wish_pin_id} 로 pins 를 조회하여 최신 이름을 노출한다.</p>
 */
public record WishConvertedEvent(Long groupId, Long pinId, Long triggerUserId, String placeName) {
}
