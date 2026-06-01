package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * cursor 기반 최신순 조회. {@code cursor}가 {@code null}이면 전체 최신부터, non-null이면 {@code id < cursor}.
     * {@code deleted_at IS NULL}만 포함하며 {@code id DESC} 정렬. 반환 개수는 {@code pageable}로 제한한다.
     */
    @Query("SELECT m FROM ChatMessage m "
            + "WHERE m.roomId = :roomId "
            + "AND (:cursor IS NULL OR m.id < :cursor) "
            + "AND m.deletedAt IS NULL "
            + "ORDER BY m.id DESC")
    List<ChatMessage> findByRoomIdBeforeCursor(@Param("roomId") Long roomId,
                                               @Param("cursor") Long cursor,
                                               Pageable pageable);

    /**
     * 계정 삭제 시 본인 발신 메시지의 {@code sender_user_id}를 NULL 처리한다(PR-3). 벌크 갱신은
     * {@code @PreUpdate}를 우회하므로 {@code updatedAt}도 명시적으로 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatMessage m SET m.senderUserId = NULL, m.updatedAt = :now "
            + "WHERE m.senderUserId = :userId AND m.deletedAt IS NULL")
    int nullifySenderByUserId(@Param("userId") Long userId, @Param("now") ZonedDateTime now);
}
