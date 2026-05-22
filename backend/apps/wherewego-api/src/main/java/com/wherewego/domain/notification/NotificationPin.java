package com.wherewego.domain.notification;

import com.wherewego.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 알림-핀 연결 엔티티. V007 스키마 {@code notification_pins} 테이블 매핑.
 *
 * <p>하나의 {@link Notification}이 참조하는 핀들을 {@code sortOrder} 순서로 보관한다.</p>
 */
@Entity
@Getter
@Table(name = "notification_pins")
public class NotificationPin extends BaseEntity {

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "pin_id", nullable = false)
    private Long pinId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected NotificationPin() { }

    private NotificationPin(Long notificationId, Long pinId, int sortOrder) {
        this.notificationId = notificationId;
        this.pinId = pinId;
        this.sortOrder = sortOrder;
    }

    public static NotificationPin link(Long notificationId, Long pinId, int sortOrder) {
        return new NotificationPin(notificationId, pinId, sortOrder);
    }
}
