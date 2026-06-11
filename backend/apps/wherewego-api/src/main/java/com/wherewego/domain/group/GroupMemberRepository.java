package com.wherewego.domain.group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository {

    /**
     * 사용자의 가장 최근 활성 그룹 ID. 활성 = {@code left_at IS NULL}.
     */
    Optional<Long> findLatestActiveGroupIdByUserId(Long userId);

    GroupMember save(GroupMember member);

    /**
     * 사용자의 활성 그룹 목록 (GM-1, FR-4/FR-5).
     * 정렬: joined_at 오름차순(가입 순), 동률 시 id 오름차순. 신규 쿼리라 공유 scope 영향 없음.
     */
    List<GroupSummary> listActiveGroupSummariesByUserId(Long userId);

    /**
     * 그룹의 활성 멤버 목록 (GM-2 그룹관리). User 닉네임 join.
     * 정렬: joined_at 오름차순(가입 순), 동률 시 id 오름차순 → 첫 항목 = 방장(owner).
     */
    List<GroupMemberInfo> listActiveMembersByGroupId(Long groupId);

    /**
     * 여러 그룹의 활성 멤버 아바타 raw 행을 IN 쿼리 1회로 조회한다 (GP-1, 그룹 목록 멤버 프리뷰).
     * <p>정렬: joined_at 오름차순(가입 순), 동률 시 id 오름차순 → 그룹별 가입순 아바타 일렬 노출.
     * 유효 프사 URL 규칙(thumb 키 우선 → 카카오 URL 폴백 → null)은 서비스에서 적용한다.
     * 빈 입력은 빈 리스트.</p>
     */
    List<GroupMemberAvatarRow> listActiveMembersByGroupIds(Collection<Long> groupIds);

    /**
     * 활성 그룹 ID 목록. group_id 오름차순(다중 비관락 데드락 방지 결정론적 순서).
     * UserDeletion 전체 순회 탈퇴에서 사용 (GM-1, FR-7).
     */
    List<Long> listActiveGroupIdsByUserId(Long userId);

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
