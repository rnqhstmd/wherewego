package com.wherewego.domain.notification;

import com.wherewego.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * 알림 엔티티. V007 스키마 {@code notifications} 테이블 매핑.
 *
 * <p>수신자(receiver)별로 발행되며, 등록자(registeredBy)/그룹(groupId)/타입(type) 메타데이터를 가진다.
 * 읽음 처리는 {@link #markRead(Instant)}를 통해 idempotent 하게 동작한다.</p>
 */
@Entity
@Getter
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    @Column(name = "registered_by", nullable = false)
    private Long registeredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private NotificationType type;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() { }

    private Notification(Long groupId, Long receiverId, Long registeredBy, NotificationType type) {
        this.groupId = groupId;
        this.receiverId = receiverId;
        this.registeredBy = registeredBy;
        this.type = type;
    }

    public static Notification create(Long groupId, Long receiverId, Long registeredBy, NotificationType type) {
        return new Notification(groupId, receiverId, registeredBy, type);
    }

    /**
     * 읽음 처리. {@code readAt}이 null일 때만 설정하여 idempotent 하게 동작한다.
     */
    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }

    public boolean isUnread() {
        return this.readAt == null;
    }
}
