package com.wherewego.infrastructure.notification;

import com.wherewego.domain.notification.NotificationPin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface NotificationPinJpaRepository extends JpaRepository<NotificationPin, Long> {

    List<NotificationPin> findByNotificationIdOrderBySortOrderAsc(Long notificationId);

    List<NotificationPin> findByNotificationIdInOrderByNotificationIdAscSortOrderAsc(
            Collection<Long> notificationIds);
}
