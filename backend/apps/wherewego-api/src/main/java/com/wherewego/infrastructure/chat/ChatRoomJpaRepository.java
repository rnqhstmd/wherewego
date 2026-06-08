package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatRoom;
import com.wherewego.domain.chat.ChatRoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface ChatRoomJpaRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findFirstByOwnerUserIdAndGroupIdAndTypeAndDeletedAtIsNull(
            Long ownerUserId, Long groupId, ChatRoomType type);

    Optional<ChatRoom> findFirstByGroupIdAndTypeAndDeletedAtIsNull(Long groupId, ChatRoomType type);

    /**
     * 계정 삭제 시 본인 소유 봇 방(type=BOT)의 활성 행을 soft delete 한다(PR-3). 벌크 갱신은
     * {@code @PreUpdate}를 우회하므로 {@code updatedAt}도 명시적으로 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatRoom r SET r.deletedAt = :now, r.updatedAt = :now "
            + "WHERE r.ownerUserId = :ownerUserId AND r.type = :type AND r.deletedAt IS NULL")
    int softDeleteByOwner(@Param("ownerUserId") Long ownerUserId,
                          @Param("type") ChatRoomType type,
                          @Param("now") ZonedDateTime now);

    /**
     * 마지막 1인 탈퇴로 그룹이 soft delete 될 때, 해당 그룹의 커플 방(type=COUPLE)의 활성 행을 soft delete 한다.
     * 벌크 갱신은 {@code @PreUpdate}를 우회하므로 {@code updatedAt}도 명시적으로 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatRoom r SET r.deletedAt = :now, r.updatedAt = :now "
            + "WHERE r.groupId = :groupId AND r.type = :type AND r.deletedAt IS NULL")
    int softDeleteByGroup(@Param("groupId") Long groupId,
                          @Param("type") ChatRoomType type,
                          @Param("now") ZonedDateTime now);
}
