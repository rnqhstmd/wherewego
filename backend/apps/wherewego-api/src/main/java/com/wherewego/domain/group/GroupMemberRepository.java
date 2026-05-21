package com.wherewego.domain.group;

import java.util.List;
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

    /**
     * 같은 그룹의 활성 멤버 중 excludeUserId(=등록자 본인)를 제외한 user_id 목록.
     * Phase 8 알림 수신자 fan-out에 사용. MVP 2인 그룹에서는 최대 1건 반환.
     * 비어 있으면 알림 생성을 skip (엣지 케이스 7).
     */
    List<Long> findOtherActiveMemberIds(Long groupId, Long excludeUserId);
}
