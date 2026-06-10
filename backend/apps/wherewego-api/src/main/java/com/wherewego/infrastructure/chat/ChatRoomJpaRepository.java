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
     * 활성 GROUP 방 race-safe 생성(PR #118 리뷰 반영). {@code ON CONFLICT DO NOTHING} 으로
     * 동시 생성 충돌 시에도 예외가 발생하지 않아, 참여 트랜잭션이 rollback-only 로 마킹되는
     * 기존 save+catch 폴백의 결함(커밋 시 UnexpectedRollbackException)을 원천 제거한다.
     * conflict target 은 V021 부분 UNIQUE(uq_chat_room_group_group)의 술어를 명시한다.
     *
     * @return 삽입 행 수(0 = 이미 활성 방 존재)
     */
    @Modifying
    @Query(value = "INSERT INTO chat_room (type, group_id) VALUES ('GROUP', :groupId) "
            + "ON CONFLICT (group_id) WHERE type = 'GROUP' AND deleted_at IS NULL DO NOTHING",
            nativeQuery = true)
    int insertGroupRoomIfAbsent(@Param("groupId") Long groupId);

    /**
     * 활성 BOT 방 race-safe 생성(PR #118 리뷰 반영 — 봇도 동일 결함 패턴이었음).
     * conflict target 은 V020 부분 UNIQUE(uq_chat_room_bot_owner_group)의 술어를 명시한다.
     *
     * @return 삽입 행 수(0 = 이미 활성 방 존재)
     */
    @Modifying
    @Query(value = "INSERT INTO chat_room (type, owner_user_id, group_id) VALUES ('BOT', :ownerUserId, :groupId) "
            + "ON CONFLICT (owner_user_id, group_id) WHERE type = 'BOT' AND deleted_at IS NULL DO NOTHING",
            nativeQuery = true)
    int insertBotRoomIfAbsent(@Param("ownerUserId") Long ownerUserId, @Param("groupId") Long groupId);

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
     * 마지막 1인 탈퇴로 그룹이 soft delete 될 때, 해당 그룹의 그룹 방(type=GROUP)의 활성 행을 soft delete 한다.
     * 벌크 갱신은 {@code @PreUpdate}를 우회하므로 {@code updatedAt}도 명시적으로 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatRoom r SET r.deletedAt = :now, r.updatedAt = :now "
            + "WHERE r.groupId = :groupId AND r.type = :type AND r.deletedAt IS NULL")
    int softDeleteByGroup(@Param("groupId") Long groupId,
                          @Param("type") ChatRoomType type,
                          @Param("now") ZonedDateTime now);
}
