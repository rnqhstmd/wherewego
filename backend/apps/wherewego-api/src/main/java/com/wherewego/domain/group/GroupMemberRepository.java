package com.wherewego.domain.group;

import java.util.Optional;

public interface GroupMemberRepository {

    /**
     * 사용자의 가장 최근 활성 그룹 ID. 활성 = {@code left_at IS NULL}.
     */
    Optional<Long> findLatestActiveGroupIdByUserId(Long userId);
}
