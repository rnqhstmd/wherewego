package com.wherewego.interfaces.api.notification;

import com.wherewego.domain.notification.NotificationService.NotificationDetailResult;
import com.wherewego.domain.notification.NotificationService.NotificationItemResult;
import com.wherewego.domain.notification.NotificationService.NotificationListResult;
import com.wherewego.domain.notification.NotificationService.NotificationPinItemResult;
import com.wherewego.domain.notification.NotificationType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class NotificationV1Dto {

    private NotificationV1Dto() {
    }

    public record NotificationItem(
            Long id,
            NotificationType type,
            Long registeredBy,
            String registeredByNickname,
            String groupName,
            String firstPlaceName,
            int totalPinCount,
            int wishCount,
            int reelCount,
            Instant createdAt,
            Instant readAt
    ) {
        public static NotificationItem from(NotificationItemResult r) {
            return new NotificationItem(
                    r.id(),
                    r.type(),
                    r.registeredBy(),
                    r.registeredByNickname(),
                    r.groupName(),
                    r.firstPlaceName(),
                    r.totalPinCount(),
                    r.wishCount(),
                    r.reelCount(),
                    r.createdAt(),
                    r.readAt()
            );
        }
    }

    public record NotificationListResponse(
            List<NotificationItem> items,
            long unreadCount
    ) {
        public static NotificationListResponse from(NotificationListResult r) {
            return new NotificationListResponse(
                    r.items().stream().map(NotificationItem::from).toList(),
                    r.unreadCount()
            );
        }
    }

    public record PinItem(
            Long pinId,
            String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean deleted,
            String instagramUrl,
            String memo,
            String tag
    ) {
        public static PinItem from(NotificationPinItemResult r) {
            return new PinItem(
                    r.pinId(),
                    r.placeName(),
                    r.address(),
                    r.latitude(),
                    r.longitude(),
                    r.deleted(),
                    r.instagramUrl(),
                    r.memo(),
                    r.tag()
            );
        }
    }

    public record NotificationDetailResponse(
            Long id,
            NotificationType type,
            String registeredByNickname,
            String groupName,
            Instant createdAt,
            List<PinItem> pins
    ) {
        public static NotificationDetailResponse from(NotificationDetailResult r) {
            return new NotificationDetailResponse(
                    r.id(),
                    r.type(),
                    r.registeredByNickname(),
                    r.groupName(),
                    r.createdAt(),
                    r.pins().stream().map(PinItem::from).toList()
            );
        }
    }

    public record ReadAllResponse(int updatedCount) {
    }
}
