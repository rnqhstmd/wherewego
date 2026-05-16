package com.wherewego.domain.group;

import java.util.Optional;

public interface GroupRepository {

    Group save(Group group);

    Optional<Group> findById(Long groupId);

    /**
     * 비관적 락(SELECT FOR UPDATE)으로 그룹 행을 조회한다.
     * 마지막 멤버 탈퇴 / 초대 수락 시 그룹 상태 전이를 직렬화한다.
     */
    Optional<Group> findByIdForUpdate(Long groupId);
}
