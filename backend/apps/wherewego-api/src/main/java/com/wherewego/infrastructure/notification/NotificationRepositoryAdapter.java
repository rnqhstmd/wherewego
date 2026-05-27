package com.wherewego.infrastructure.notification;

import com.wherewego.domain.notification.Notification;
import com.wherewego.domain.notification.NotificationPin;
import com.wherewego.domain.notification.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class NotificationRepositoryAdapter implements NotificationRepository {

    private final NotificationJpaRepository notificationJpa;
    private final NotificationPinJpaRepository notificationPinJpa;

    @Override
    public Notification save(Notification notification) {
        return notificationJpa.save(notification);
    }

    @Override
    public List<NotificationPin> saveAllPins(List<NotificationPin> links) {
        return notificationPinJpa.saveAll(links);
    }

    @Override
    public List<Notification> findRecentByReceiverId(Long receiverId, int limit) {
        return notificationJpa.findByReceiverIdOrderByCreatedAtDesc(receiverId, PageRequest.of(0, limit));
    }

    @Override
    public Optional<Notification> findByIdAndReceiverId(Long notificationId, Long receiverId) {
        return notificationJpa.findByIdAndReceiverId(notificationId, receiverId);
    }

    @Override
    public List<NotificationPin> findPinsByNotificationId(Long notificationId) {
        return notificationPinJpa.findByNotificationIdOrderBySortOrderAsc(notificationId);
    }

    @Override
    public Map<Long, List<NotificationPin>> findPinsByNotificationIds(Collection<Long> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return Map.of();
        }
        return notificationPinJpa
                .findByNotificationIdInOrderByNotificationIdAscSortOrderAsc(notificationIds)
                .stream()
                .collect(Collectors.groupingBy(NotificationPin::getNotificationId));
    }

    @Override
    public long countUnreadByReceiverId(Long receiverId) {
        return notificationJpa.countByReceiverIdAndReadAtIsNull(receiverId);
    }

    @Override
    public boolean existsByGroupIdAndReceiverIdAndRegisteredByAndWishPinId(
            Long groupId, Long receiverId, Long registeredBy, Long wishPinId) {
        return notificationJpa.existsByGroupIdAndReceiverIdAndRegisteredByAndWishPinId(
                groupId, receiverId, registeredBy, wishPinId);
    }

    @Override
    public int markAllReadByReceiverId(Long receiverId, Instant now) {
        // BaseEntity.updatedAt 은 ZonedDateTime 매핑이므로 JPQL UPDATE 시 동일 시점을 ZDT 로도 전달한다.
        ZonedDateTime nowZdt = now.atZone(ZoneId.systemDefault());
        return notificationJpa.markAllRead(receiverId, now, nowZdt);
    }
}
