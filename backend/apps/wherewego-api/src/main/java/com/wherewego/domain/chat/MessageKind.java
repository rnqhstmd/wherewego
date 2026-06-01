package com.wherewego.domain.chat;

/**
 * P2: 채팅 메시지 종류. payload_json 스키마를 결정한다.
 * TEXT 일반 텍스트, PLACE_CARDS 봇 장소 추출 결과 카드, MEMO_PROMPT 메모 입력 유도,
 * PROCESSING 봇 처리중 플레이스홀더, SYSTEM 시스템 안내/오류.
 */
public enum MessageKind {
    TEXT,
    PLACE_CARDS,
    MEMO_PROMPT,
    PROCESSING,
    SYSTEM
}
