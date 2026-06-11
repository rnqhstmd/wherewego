package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.GroupMember;
import com.wherewego.domain.group.GroupMemberInfo;
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

    // GP-1: imageKey/imageThumbKey 를 projection 에 추가(서비스가 toPublicUrl 로 URL 치환). 그룹 대표 이미지 노출.
    @Query("SELECT new com.wherewego.domain.group.GroupSummary(g.id, g.name, g.createdAt, "
            + "(SELECT COUNT(m2) FROM GroupMember m2 WHERE m2.groupId = g.id AND m2.leftAt IS NULL), "
            + "g.imageKey, g.imageThumbKey) "
            + "FROM GroupMember gm JOIN GroupAggregate g ON g.id = gm.groupId "
            + "WHERE gm.userId = :userId AND gm.leftAt IS NULL AND g.deletedAt IS NULL "
            + "ORDER BY gm.joinedAt ASC, gm.id ASC")
    List<GroupSummary> findActiveGroupSummariesByUserId(@Param("userId") Long userId);

    // GP-1: 여러 그룹의 활성 멤버 아바타 raw 행을 IN 1회로. 정렬 joined_at ASC, id ASC → 그룹별 가입순.
    //   유효 프사 URL 규칙(thumb 키 우선 → 카카오 URL 폴백)은 서비스가 적용한다.
    @Query("SELECT new com.wherewego.domain.group.GroupMemberAvatarRow("
            + "gm.groupId, u.id, u.nickname, u.profileImageThumbKey, u.profileImageUrl) "
            + "FROM GroupMember gm JOIN UserModel u ON u.id = gm.userId "
            + "WHERE gm.groupId IN :groupIds AND gm.leftAt IS NULL "
            + "ORDER BY gm.joinedAt ASC, gm.id ASC")
    List<com.wherewego.domain.group.GroupMemberAvatarRow> findActiveMembersByGroupIds(
            @Param("groupIds") java.util.Collection<Long> groupIds);

    // GM-2 그룹관리: 그룹의 활성 멤버 + User 닉네임 join. 정렬 joined_at ASC, id ASC → 첫 항목 = 방장.
    // GP-1 FR-9: 유효 프사 URL 산출용 thumb 키/카카오 URL 도 함께 projection(서비스가 규칙 적용).
    @Query("SELECT new com.wherewego.domain.group.GroupMemberInfo("
            + "gm.userId, u.nickname, gm.joinedAt, gm.id, u.profileImageThumbKey, u.profileImageUrl) "
            + "FROM GroupMember gm JOIN UserModel u ON u.id = gm.userId "
            + "WHERE gm.groupId = :groupId AND gm.leftAt IS NULL "
            + "ORDER BY gm.joinedAt ASC, gm.id ASC")
    List<GroupMemberInfo> findActiveMembersByGroupId(@Param("groupId") Long groupId);

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
