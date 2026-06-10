package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatRoomRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatRoomReadJpaRepository extends JpaRepository<ChatRoomRead, Long> {

    Optional<ChatRoomRead> findFirstByRoomIdAndUserId(Long roomId, Long userId);

    /**
     * 읽음 행 race-safe 생성(PR #118 리뷰 반영). {@code ON CONFLICT DO NOTHING} 으로
     * 동시 최초 조회 충돌 시에도 예외가 발생하지 않아, 참여 트랜잭션 rollback-only 마킹
     * (기존 save+catch 폴백의 결함 — getMessages 전체 실패)을 원천 제거한다.
     * conflict target 은 V021 UNIQUE(uq_chat_room_reads_room_user)다.
     *
     * @return 삽입 행 수(0 = 이미 존재)
     */
    @Modifying
    @Query(value = "INSERT INTO chat_room_reads (room_id, user_id) VALUES (:roomId, :userId) "
            + "ON CONFLICT (room_id, user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("roomId") Long roomId, @Param("userId") Long userId);
}
