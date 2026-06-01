package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
