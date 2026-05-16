package com.wherewego.domain.group;

import java.util.Optional;

public interface GroupMemberRepository {

    /**
     * 사용자의 가장 최근 활성 그룹 ID. 활성 = {@code left_at IS NULL}.
     */
    Optional<Long> findLatestActiveGroupIdByUserId(Long userId);

    GroupMember save(GroupMember member);

    /** 활성 그룹 멤버십 존재 여부 (1인 1활성 그룹 제약 사전 검사). */
    boolean existsActiveByUserId(Long userId);

    /** 활성 GroupMember 단건 조회 (권한 검사 / 탈퇴 진입점). */
    Optional<GroupMember> findActiveByGroupIdAndUserId(Long groupId, Long userId);

    /** 그룹의 활성 멤버 수 (마지막 멤버 판정 / 정원 검사). */
    long countActiveByGroupId(Long groupId);
}
