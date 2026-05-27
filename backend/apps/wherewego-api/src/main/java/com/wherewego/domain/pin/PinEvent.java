package com.wherewego.domain.pin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * Phase 12: 핀 관심 표현(WANT) 이력 엔티티. V012 {@code pin_events} 테이블 매핑.
 *
 * <p><b>{@link com.wherewego.domain.BaseEntity} 미상속</b>: 본 테이블은 이력성(append/delete)이며
 * {@code updated_at} / {@code deleted_at} 의미가 없다. WANT 취소는 row hard DELETE 로 처리하며,
 * 부분 UNIQUE 인덱스 {@code uq_pin_events_pin_user_want} {@code (pin_id, user_id) WHERE action='WANT'}
 * 가 영구 멱등(D-19)을 보장한다.</p>
 *
 * <p>{@code created_at} 은 DB DEFAULT {@code now()} 로 채워지지만 INSERT 후에도 JPA 가 값을 읽을 수 있도록
 * 엔티티 생성 시 애플리케이션에서 {@link ZonedDateTime#now()} 를 명시적으로 부여한다 (Pin 엔티티가
 * {@code BaseEntity.@PrePersist} 로 동일하게 처리하는 패턴 답습).</p>
 */
@Entity
@Getter
@Table(
        name = "pin_events",
        indexes = {
                @Index(name = "idx_pin_events_pin_id", columnList = "pin_id"),
                @Index(name = "idx_pin_events_group_created", columnList = "group_id, created_at DESC")
        }
)
public class PinEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pin_id", nullable = false)
    private Long pinId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private PinEventAction action;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    protected PinEvent() { }

    private PinEvent(Long pinId, Long userId, Long groupId, PinEventAction action) {
        this.pinId = pinId;
        this.userId = userId;
        this.groupId = groupId;
        this.action = action;
        this.createdAt = ZonedDateTime.now();
    }

    /**
     * WANT 이벤트 생성 팩토리. 부분 UNIQUE 인덱스 {@code uq_pin_events_pin_user_want} 와 결합되어
     * 동일 {@code (pin_id, user_id)} 에 대한 중복 INSERT 는 {@code DataIntegrityViolationException}
     * 으로 차단된다.
     */
    public static PinEvent wantOf(Long pinId, Long userId, Long groupId) {
        return new PinEvent(pinId, userId, groupId, PinEventAction.WANT);
    }
}
