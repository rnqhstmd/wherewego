package com.wherewego.domain.chat;

/**
 * P2 / GC-1: 채팅 메시지 종류. payload_json 스키마를 결정한다.
 * TEXT 일반 텍스트, PLACE_CARDS 봇 장소 추출 결과 카드, MEMO_PROMPT 메모 입력 유도,
 * PROCESSING 봇 처리중 플레이스홀더, SYSTEM 시스템 안내/오류,
 * REEL_LINK 그룹 방 인스타 릴스 링크(payload {@code {"url": ..., "thumbnailKey": null}} — GC-1),
 * PIN_REPLY 지도 핀 답장(payload {@code {"pinId": ..., "text": ...}} — 핀 카드 + 텍스트로 표시).
 */
public enum MessageKind {
    TEXT,
    PLACE_CARDS,
    MEMO_PROMPT,
    PROCESSING,
    SYSTEM,
    REEL_LINK,
    PIN_REPLY
}
