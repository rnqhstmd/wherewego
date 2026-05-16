package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.GroupMember;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupMemberJpaRepository extends JpaRepository<GroupMember, Long> {

    @Query("SELECT gm.groupId FROM GroupMember gm "
            + "WHERE gm.userId = :userId AND gm.leftAt IS NULL "
            + "ORDER BY gm.id DESC")
    List<Long> findActiveGroupIdsByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(gm) > 0 THEN true ELSE false END "
            + "FROM GroupMember gm "
            + "WHERE gm.userId = :userId AND gm.leftAt IS NULL")
    boolean existsActiveByUserId(@Param("userId") Long userId);

    @Query("SELECT gm FROM GroupMember gm "
            + "WHERE gm.groupId = :gid AND gm.userId = :uid AND gm.leftAt IS NULL")
    Optional<GroupMember> findActiveByGroupIdAndUserId(
            @Param("gid") Long groupId,
            @Param("uid") Long userId);

    @Query("SELECT COUNT(gm) FROM GroupMember gm "
            + "WHERE gm.groupId = :gid AND gm.leftAt IS NULL")
    long countActiveByGroupId(@Param("gid") Long groupId);
}
