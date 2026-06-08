package com.wherewego.domain.chat;

import com.wherewego.domain.BaseEntity;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * P2: 채팅 방. V015 스키마 {@code chat_room} 테이블 매핑.
 *
 * <p>유형(type)에 따라 식별자가 분기된다: BOT 은 {@code ownerUserId} + {@code groupId}(GM-2 그룹별 봇 방),
 * COUPLE 은 {@code groupId} 만 보유한다. 활성 방 1개 강제는 부분 UNIQUE 인덱스
 * (V020 uq_chat_room_bot_owner_group / V015 uq_chat_room_couple_group)와 결합한다.
 * 불변식은 {@link #guard()}에서 검증한다.</p>
 *
 * <p>GM-2: 봇 방은 owner 1명 전용이므로 읽음 추적을 {@code lastReadMessageId} 단일 컬럼(V020)으로 보유한다.</p>
 */
@Entity
@Getter
@Table(name = "chat_room")
public class ChatRoom extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ChatRoomType type;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    protected ChatRoom() { }

    private ChatRoom(ChatRoomType type, Long groupId, Long ownerUserId) {
        this.type = type;
        this.groupId = groupId;
        this.ownerUserId = ownerUserId;
        guard();
    }

    @Override
    protected void guard() {
        if (type == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "type은 비어있을 수 없습니다.");
        }
        if (type == ChatRoomType.BOT) {
            // GM-2: 봇 방을 그룹별로(owner+group) 재정의 — ownerUserId·groupId 모두 필수.
            if (ownerUserId == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "봇 방은 ownerUserId가 필요합니다.");
            }
            if (groupId == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "봇 방은 groupId가 필요합니다.");
            }
        }
        if (type == ChatRoomType.COUPLE) {
            if (groupId == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "커플 방은 groupId가 필요합니다.");
            }
            if (ownerUserId != null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "커플 방은 ownerUserId를 가질 수 없습니다.");
            }
        }
    }

    public static ChatRoom createBotRoom(Long ownerUserId, Long groupId) {
        return new ChatRoom(ChatRoomType.BOT, groupId, ownerUserId);
    }

    public static ChatRoom createCoupleRoom(Long groupId) {
        return new ChatRoom(ChatRoomType.COUPLE, groupId, null);
    }

    public boolean isActive() {
        return getDeletedAt() == null;
    }

    /**
     * 봇 방 읽음 포인터를 갱신한다(GM-2, FR-5). 더 큰 messageId 로만 전진하여 역행을 방지한다
     * (구 페이지 재조회로 작은 id 가 들어와도 이미 읽은 지점이 후퇴하지 않는다). JPA dirty checking 으로
     * 호출자 트랜잭션의 {@code save} 시 반영된다.
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
