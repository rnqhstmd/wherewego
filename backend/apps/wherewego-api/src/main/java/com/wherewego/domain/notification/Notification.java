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

    /**
     * Phase 12: WISH_CONVERTED 알림에서만 채워지는 핀 참조. 부분 UNIQUE 인덱스
     * {@code uq_notifications_wish_converted} (group_id, receiver_id, registered_by, wish_pin_id) 와
     * 결합되어 동일 핀에 대한 중복 WISH 전환 알림을 race-free 하게 차단한다.
     * 다른 타입(MANUAL_PIN/CHATBOT_PINS/VISIT_DETECTED)에서는 NULL 유지.
     *
     * <p>V010 {@link #visitPinId} 와 동일한 정책: 본 행에 직접 핀 참조를 보관하며,
     * {@code NotificationPin} 링크 테이블은 WISH_CONVERTED 타입에서는 사용하지 않는다.</p>
     */
    @Column(name = "wish_pin_id")
    private Long wishPinId;

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

    /**
     * WISH_CONVERTED 전용 private 생성자. {@link #visitPinId} 5-인자 생성자와 시그니처 충돌을 피하기 위해
     * boolean 마커 인자를 추가하여 분기한다 (V009 visit 패턴과 동일한 의도, 다른 컬럼 매핑).
     */
    private Notification(Long groupId, Long receiverId, Long registeredBy,
                         NotificationType type, Long wishPinId, boolean wishMarker) {
        this.groupId = groupId;
        this.receiverId = receiverId;
        this.registeredBy = registeredBy;
        this.type = type;
        this.wishPinId = wishPinId;
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
     * Phase 12: WISH_CONVERTED 알림 팩토리. {@code wishPinId} 는 부분 UNIQUE 인덱스
     * {@code uq_notifications_wish_converted} 의 키 컬럼이므로 non-null 이어야 한다 (호출자에서 보장).
     *
     * <p>V009 {@link #createForVisit} 팩토리 패턴을 그대로 답습한다. {@link #create} 일반 팩토리는
     * {@code wish_pin_id=NULL} 을 남기므로 WISH_CONVERTED 타입에 사용 금지.</p>
     */
    public static Notification createForWishConverted(Long groupId, Long receiverId, Long registeredBy, Long wishPinId) {
        return new Notification(groupId, receiverId, registeredBy, NotificationType.WISH_CONVERTED, wishPinId, true);
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
