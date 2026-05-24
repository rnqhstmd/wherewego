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

    /**
     * Phase 10: VISIT_DETECTED 알림에서만 채워지는 핀 참조. 부분 UNIQUE 인덱스
     * {@code uq_notifications_visit} (group_id, receiver_id, registered_by, visit_pin_id) 와 결합되어
     * 동일 핀에 대한 중복 알림을 race-free 하게 차단한다. MANUAL_PIN/CHATBOT_PINS 는 NULL 유지.
     */
    @Column(name = "visit_pin_id")
    private Long visitPinId;

    protected Notification() { }

    private Notification(Long groupId, Long receiverId, Long registeredBy, NotificationType type) {
        this.groupId = groupId;
        this.receiverId = receiverId;
        this.registeredBy = registeredBy;
        this.type = type;
    }

    private Notification(Long groupId, Long receiverId, Long registeredBy,
                         NotificationType type, Long visitPinId) {
        this.groupId = groupId;
        this.receiverId = receiverId;
        this.registeredBy = registeredBy;
        this.type = type;
        this.visitPinId = visitPinId;
    }

    public static Notification create(Long groupId, Long receiverId, Long registeredBy, NotificationType type) {
        return new Notification(groupId, receiverId, registeredBy, type);
    }

    /**
     * VISIT_DETECTED 알림 팩토리. {@code visitPinId} 는 부분 UNIQUE 인덱스의 키 컬럼이므로
     * non-null 이어야 한다 (호출자에서 보장).
     */
    public static Notification createForVisit(Long groupId, Long receiverId, Long registeredBy, Long visitPinId) {
        return new Notification(groupId, receiverId, registeredBy, NotificationType.VISIT_DETECTED, visitPinId);
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
