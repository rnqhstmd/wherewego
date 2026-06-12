package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.GroupMember;
import com.wherewego.domain.group.GroupMemberAvatarRow;
import com.wherewego.domain.group.GroupMemberInfo;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class GroupMemberRepositoryImpl implements GroupMemberRepository {

    private final GroupMemberJpaRepository jpaRepository;

    @Override
    public Optional<Long> findLatestActiveGroupIdByUserId(Long userId) {
        List<Long> result = jpaRepository.findActiveGroupIdsByUserId(userId, PageRequest.of(0, 1));
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public GroupMember save(GroupMember member) {
        // saveAndFlush: leaveGroup이 markLeft → save → countActiveByGroupId 순서로 호출할 때
        // JPA 자동 flush가 누락되어 count가 stale을 반환하는 경우를 방지한다.
        return jpaRepository.saveAndFlush(member);
    }

    @Override
    public List<GroupSummary> listActiveGroupSummariesByUserId(Long userId) {
        return jpaRepository.findActiveGroupSummariesByUserId(userId);
    }

    @Override
    public List<GroupMemberInfo> listActiveMembersByGroupId(Long groupId) {
        return jpaRepository.findActiveMembersByGroupId(groupId);
    }

    @Override
    public List<GroupMemberAvatarRow> listActiveMembersByGroupIds(Collection<Long> groupIds) {
        // 빈 IN 절은 일부 DB 에서 오류/전체스캔이라 호출 전 차단.
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findActiveMembersByGroupIds(groupIds);
    }

    @Override
    public List<Long> listActiveGroupIdsByUserId(Long userId) {
        return jpaRepository.findActiveGroupIdsByUserIdOrderByGroupId(userId);
    }

    @Override
    public Optional<GroupMember> findActiveByGroupIdAndUserId(Long groupId, Long userId) {
        return jpaRepository.findActiveByGroupIdAndUserId(groupId, userId);
    }

    @Override
    public long countActiveByGroupId(Long groupId) {
        return jpaRepository.countActiveByGroupId(groupId);
    }

    @Override
    public List<Long> findOtherActiveMemberIds(Long groupId, Long excludeUserId) {
        return jpaRepository.findOtherActiveMemberIds(groupId, excludeUserId);
    }
}
