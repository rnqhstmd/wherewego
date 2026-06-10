package com.wherewego.domain.chat;

import com.wherewego.domain.BaseEntity;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * GC-1: 그룹 방 멤버별 읽음 포인터. V021 스키마 {@code chat_room_reads} 테이블 매핑(FR-GC1-2).
 *
 * <p>(room, user)당 1행을 V021 UNIQUE 제약(uq_chat_room_reads_room_user)이 강제한다.
 * 봇 방의 {@code chat_room.last_read_message_id}(V020, owner 전용 단일 포인터)와 달리
 * 그룹 방은 멤버마다 독립적인 읽음 지점을 가진다 — 방 목록 unread 판정의 기반.</p>
 */
@Entity
@Getter
@Table(name = "chat_room_reads")
public class ChatRoomRead extends BaseEntity {

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    protected ChatRoomRead() { }

    private ChatRoomRead(Long roomId, Long userId) {
        this.roomId = roomId;
        this.userId = userId;
        guard();
    }

    @Override
    protected void guard() {
        if (roomId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "roomId는 비어있을 수 없습니다.");
        }
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 비어있을 수 없습니다.");
        }
    }

    public static ChatRoomRead create(Long roomId, Long userId) {
        return new ChatRoomRead(roomId, userId);
    }

    /**
     * 읽음 포인터를 갱신한다. 더 큰 messageId 로만 전진하여 역행을 방지한다
     * ({@link ChatRoom#markRead} 동형 — 구 페이지 재조회로 작은 id 가 들어와도 후퇴하지 않는다).
     * JPA dirty checking 으로 호출자 트랜잭션의 {@code save} 시 반영된다.
     *
     * @param messageId 방의 최신 메시지 id. {@code null} 이면 무시.
     */
    public void markRead(Long messageId) {
        if (messageId == null) {
            return;
        }
        if (lastReadMessageId == null || lastReadMessageId < messageId) {
            this.lastReadMessageId = messageId;
        }
    }
}
