package com.wherewego.domain.chat;

/**
 * P2 / GC-1: 채팅 방 유형.
 * BOT 은 사용자 1:1 봇 방(owner_user_id 보유), GROUP 은 그룹 공유 방(group_id 보유).
 * (GC-1: COUPLE 을 GROUP 으로 일반화 — V021 에서 데이터/인덱스 함께 전환.)
 */
public enum ChatRoomType {
    BOT,
    GROUP
}
