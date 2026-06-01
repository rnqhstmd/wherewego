package com.wherewego.domain.chat;

/**
 * P2: 채팅 방 유형.
 * BOT 은 사용자 1:1 봇 방(owner_user_id 보유), COUPLE 은 그룹 공유 방(group_id 보유).
 */
public enum ChatRoomType {
    BOT,
    COUPLE
}
