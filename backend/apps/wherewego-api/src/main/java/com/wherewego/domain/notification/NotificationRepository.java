package com.wherewego.domain.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 알림 도메인 port. {@code notifications} / {@code notification_pins} 두 테이블에 대한
 * 접근을 단일 인터페이스로 노출한다. JPA 어댑터({@code NotificationRepositoryAdapter})가
 * 두 Spring Data 리포지토리를 위임하여 구현한다.
 */
public interface NotificationRepository {

    Notification save(Notification notification);

    List<NotificationPin> saveAllPins(List<NotificationPin> links);

    List<Notification> findRecentByReceiverId(Long receiverId, int limit);

    Optional<Notification> findByIdAndReceiverId(Long notificationId, Long receiverId);

    List<NotificationPin> findPinsByNotificationId(Long notificationId);

    Map<Long, List<NotificationPin>> findPinsByNotificationIds(Collection<Long> notificationIds);

    long countUnreadByReceiverId(Long receiverId);

    int markAllReadByReceiverId(Long receiverId, Instant now);

    /**
     * PR #76 Gemini #3: WISH_CONVERTED 알림 중복 사전 조회. 부분 UNIQUE 인덱스
     * {@code uq_notifications_wish_converted} 와 동일한 키 조합으로 존재 여부를 확인한다.
     *
     * <p>{@code createForWishConverted} 가 INSERT 전에 본 메서드로 중복을 사전 차단하여
     * {@link org.springframework.dao.DataIntegrityViolationException} 발생 시 transaction 이
     * rollback-only 마크되는 부작용(같은 트랜잭션 내 다른 receiver INSERT 가 모두 rollback)을 회피한다.</p>
     */
    boolean existsByGroupIdAndReceiverIdAndRegisteredByAndWishPinId(
            Long groupId, Long receiverId, Long registeredBy, Long wishPinId);
}
