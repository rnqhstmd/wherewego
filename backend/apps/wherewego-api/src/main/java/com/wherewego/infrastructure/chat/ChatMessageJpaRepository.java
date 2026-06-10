package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessage, Long> {

    /** GC-1: 방 소속 활성 메시지 단건 조회(온디맨드 추출 대상 검증). */
    Optional<ChatMessage> findByIdAndRoomIdAndDeletedAtIsNull(Long id, Long roomId);

    /** GC-3: 활성 메시지 단건 조회(비동기 썸네일 반영 — roomId 컨텍스트 없는 백그라운드 스레드용). */
    Optional<ChatMessage> findByIdAndDeletedAtIsNull(Long id);

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
     * 미읽음 수: 읽음 포인터({@code afterId}, {@code null}=전부) 이후의 타인(sender NULL 포함) 활성 메시지 수.
     * {@code idx_chat_message_room_id_desc (room_id, id DESC)} 인덱스 range scan 으로 커버된다.
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m "
            + "WHERE m.roomId = :roomId "
            + "AND (:afterId IS NULL OR m.id > :afterId) "
            + "AND (m.senderUserId IS NULL OR m.senderUserId <> :userId) "
            + "AND m.deletedAt IS NULL")
    long countOthersAfter(@Param("roomId") Long roomId,
                          @Param("afterId") Long afterId,
                          @Param("userId") Long userId);

    /**
     * 계정 삭제 시 본인 발신 메시지의 {@code sender_user_id}를 NULL 처리한다(PR-3). 벌크 갱신은
     * {@code @PreUpdate}를 우회하므로 {@code updatedAt}도 명시적으로 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatMessage m SET m.senderUserId = NULL, m.updatedAt = :now "
            + "WHERE m.senderUserId = :userId AND m.deletedAt IS NULL")
    int nullifySenderByUserId(@Param("userId") Long userId, @Param("now") ZonedDateTime now);
}
