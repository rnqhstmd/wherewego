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
 * <p>유형(type)에 따라 식별자가 분기된다: BOT 은 {@code ownerUserId} 만, COUPLE 은 {@code groupId} 만 보유한다.
 * 활성 방 1개 강제는 V015 부분 UNIQUE 인덱스(uq_chat_room_bot_owner/uq_chat_room_couple_group)와 결합한다.
 * 불변식은 {@link #guard()}에서 검증한다.</p>
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
            if (ownerUserId == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "봇 방은 ownerUserId가 필요합니다.");
            }
            if (groupId != null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "봇 방은 groupId를 가질 수 없습니다.");
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

    public static ChatRoom createBotRoom(Long ownerUserId) {
        return new ChatRoom(ChatRoomType.BOT, null, ownerUserId);
    }

    public static ChatRoom createCoupleRoom(Long groupId) {
        return new ChatRoom(ChatRoomType.COUPLE, groupId, null);
    }

    public boolean isActive() {
        return getDeletedAt() == null;
    }
}
