package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

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
}
