package com.wherewego.domain.group;

import java.time.ZonedDateTime;

/**
 * 내 그룹 목록 항목 (GM-1, FR-4/FR-5).
 *
 * <p>{@code createdAt} 은 그룹 생성 시각(Group.createdAt = BaseEntity ZonedDateTime) 기준이다.
 * GroupMember.joinedAt(Instant) 과 다른 컬럼임에 유의.</p>
 */
public record GroupSummary(
        Long groupId,
        String name,
        ZonedDateTime createdAt,
        long memberCount
) {
}
