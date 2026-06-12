package com.wherewego.domain.pin;

import com.wherewego.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 핀 방문 기록(정책 v2). V023 스키마 {@code pin_visits} 테이블 매핑.
 *
 * <p>(pin_id, user_id) 당 1행이며 재방문/동행 union 은 서비스가 핀 비관 락 안에서 upsert 로 결정한다
 * (select → insert/update — ON CONFLICT 불필요). {@code visitedAt} 은 서버 now 로 기록한다(감지 직후 호출 전제).
 * {@link VisitSource#TAGGED} 행은 본인 직접 체크인 시 {@link VisitSource#SELF} 로 승격되며 역방향 강등은 없다.</p>
 */
@Entity
@Getter
@Table(
        name = "pin_visits",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_pin_visits",
                columnNames = {"pin_id", "user_id"}
        )
)
public class PinVisit extends BaseEntity {

    @Column(name = "pin_id", nullable = false)
    private Long pinId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "visited_at", nullable = false)
    private ZonedDateTime visitedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private VisitSource source;

    protected PinVisit() { }

    private PinVisit(Long pinId, Long userId, ZonedDateTime visitedAt, VisitSource source) {
        this.pinId = pinId;
        this.userId = userId;
        this.visitedAt = visitedAt;
        this.source = source;
    }

    /**
     * 신규 방문 기록 생성. {@code visitedAt} 은 호출자(서비스)가 서버 now 로 전달한다.
     */
    public static PinVisit create(Long pinId, Long userId, ZonedDateTime visitedAt, VisitSource source) {
        return new PinVisit(pinId, userId, visitedAt, source);
    }

    /**
     * 재방문/재선언 시 방문 시각을 갱신한다(visited_at 최신화). source 는 별도로 {@link #promoteToSelf()}로 다룬다.
     */
    public void touchVisitedAt(ZonedDateTime visitedAt) {
        this.visitedAt = visitedAt;
    }

    /**
     * TAGGED → SELF 승격(역방향 강등 없음). 이미 SELF 이면 변경하지 않는다(멱등, AC-4).
     */
    public void promoteToSelf() {
        if (this.source == VisitSource.TAGGED) {
            this.source = VisitSource.SELF;
        }
    }
}
