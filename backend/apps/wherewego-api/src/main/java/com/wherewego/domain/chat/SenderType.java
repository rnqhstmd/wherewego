package com.wherewego.domain.chat;

/**
 * P2: 채팅 메시지 발신 주체.
 * USER 는 사람(sender_user_id 보유), BOT 은 앱 봇, SYSTEM 은 시스템 안내/오류 메시지.
 */
public enum SenderType {
    USER,
    BOT,
    SYSTEM
}
