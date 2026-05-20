package com.wherewego.domain.group;

import java.time.ZonedDateTime;

/**
 * 사용자가 현재 속한 활성 그룹 정보.
 */
public record ActiveGroupInfo(
        Long groupId,
        String name,
        ZonedDateTime createdAt,
        long memberCount
) {
}
