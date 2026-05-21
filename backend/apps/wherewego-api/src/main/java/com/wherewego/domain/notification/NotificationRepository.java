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
}
