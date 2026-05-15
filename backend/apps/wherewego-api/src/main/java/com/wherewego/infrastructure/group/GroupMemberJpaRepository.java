package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.GroupMember;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GroupMemberJpaRepository extends JpaRepository<GroupMember, Long> {

    @Query("SELECT gm.groupId FROM GroupMember gm "
            + "WHERE gm.userId = :userId AND gm.leftAt IS NULL "
            + "ORDER BY gm.id DESC")
    List<Long> findActiveGroupIdsByUserId(@Param("userId") Long userId, Pageable pageable);
}
