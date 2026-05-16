package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.Group;
import com.wherewego.domain.group.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GroupRepositoryAdapter implements GroupRepository {

    private final GroupJpaRepository groupJpaRepository;

    @Override
    public Group save(Group group) {
        // saveAndFlush: leaveGroup이 마지막 멤버 탈퇴 후 group.markDeleted()
        // 한 변경의 UPDATE를 트랜잭션 커밋 전에 강제 flush한다.
        return groupJpaRepository.saveAndFlush(group);
    }

    @Override
    public Optional<Group> findById(Long groupId) {
        return groupJpaRepository.findById(groupId);
    }

    @Override
    public Optional<Group> findByIdForUpdate(Long groupId) {
        return groupJpaRepository.findByIdForUpdate(groupId);
    }
}
