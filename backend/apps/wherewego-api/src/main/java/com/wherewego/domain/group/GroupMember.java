package com.wherewego.domain.group;

import com.wherewego.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * Phase 3 선행 read-only 엔티티. V001 {@code group_members} 매핑.
 *
 * <p>V001 스키마 우선 적용 (설계서 §3.4 와의 차이):</p>
 * <ul>
 *     <li>설계서의 {@code role}/{@code status} enum 컬럼은 V001 에 없음 → 엔티티 미포함</li>
 *     <li>활성 여부는 {@code left_at IS NULL} 로 표현 (Partial UNIQUE 인덱스 강제)</li>
 *     <li>{@code joined_at} 컬럼 보존</li>
 * </ul>
 */
@Entity
@Getter
@Table(name = "group_members")
public class GroupMember extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    protected GroupMember() { }

    public boolean isActive() {
        return leftAt == null;
    }
}
