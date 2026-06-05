package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.GroupMember;
import com.wherewego.domain.group.GroupSummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupMemberJpaRepository extends JpaRepository<GroupMember, Long> {

    // 용도: 최신 활성 그룹 1개 조회용(Pageable limit 1, id DESC). me·onboarding·챗봇이 공유.
    //   아래 ...OrderByGroupId(group_id ASC, 전체 순회)와 정렬/용도가 다르므로 혼동 주의.
    // GM-1: me·onboarding·UserDeletion·챗봇이 공유하는 쿼리. id DESC 는 BIGSERIAL 단조성으로
    //   joined_at 최신과 동치(FR-6/AC-8 충족) → 정렬 보정 불요, 변경 시 제외 영역 동작이 바뀌므로 무변경 유지.
    @Query("SELECT gm.groupId FROM GroupMember gm "
            + "WHERE gm.userId = :userId AND gm.leftAt IS NULL "
            + "ORDER BY gm.id DESC")
    List<Long> findActiveGroupIdsByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT new com.wherewego.domain.group.GroupSummary(g.id, g.name, g.createdAt, "
            + "(SELECT COUNT(m2) FROM GroupMember m2 WHERE m2.groupId = g.id AND m2.leftAt IS NULL)) "
            + "FROM GroupMember gm JOIN GroupAggregate g ON g.id = gm.groupId "
            + "WHERE gm.userId = :userId AND gm.leftAt IS NULL AND g.deletedAt IS NULL "
            + "ORDER BY gm.joinedAt ASC, gm.id ASC")
    List<GroupSummary> findActiveGroupSummariesByUserId(@Param("userId") Long userId);

    // 용도: 활성 그룹 전체 순회용(group_id ASC). UserDeletion 의 다중 비관락 데드락 방지를 위한 결정론적 락 순서.
    //   위 findActiveGroupIdsByUserId(최신 1개, id DESC)와 정렬/용도가 다르므로 혼동 주의.
    @Query("SELECT gm.groupId FROM GroupMember gm "
            + "WHERE gm.userId = :userId AND gm.leftAt IS NULL "
            + "ORDER BY gm.groupId ASC")
    List<Long> findActiveGroupIdsByUserIdOrderByGroupId(@Param("userId") Long userId);

    @Query("SELECT gm FROM GroupMember gm "
            + "WHERE gm.groupId = :gid AND gm.userId = :uid AND gm.leftAt IS NULL")
    Optional<GroupMember> findActiveByGroupIdAndUserId(
            @Param("gid") Long groupId,
            @Param("uid") Long userId);

    @Query("SELECT COUNT(gm) FROM GroupMember gm "
            + "WHERE gm.groupId = :gid AND gm.leftAt IS NULL")
    long countActiveByGroupId(@Param("gid") Long groupId);

    @Query("SELECT gm.userId FROM GroupMember gm "
            + "WHERE gm.groupId = :groupId AND gm.userId <> :excludeUserId AND gm.leftAt IS NULL")
    List<Long> findOtherActiveMemberIds(@Param("groupId") Long groupId,
                                        @Param("excludeUserId") Long excludeUserId);
}
