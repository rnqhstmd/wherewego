package com.wherewego.domain.chat;

/**
 * P2 / GC-1: 채팅 메시지 종류. payload_json 스키마를 결정한다.
 * TEXT 일반 텍스트, PLACE_CARDS 봇 장소 추출 결과 카드, MEMO_PROMPT 메모 입력 유도,
 * PROCESSING 봇 처리중 플레이스홀더, SYSTEM 시스템 안내/오류,
 * REEL_LINK 그룹 방 인스타 릴스 링크(payload {@code {"url": ..., "thumbnailKey": null}} — GC-1),
 * PIN_REPLY 지도 핀 답장(payload {@code {"pinId": ..., "text": ...}} — 핀 카드 + 텍스트로 표시),
 * PIN_VISIT 체크인 카드(payload {@code {"pinId": ...}} — "다녀갔어요 📍", 정책 v2),
 * PIN_MEMORY 추억 카드(payload {@code {"pinId": ..., "userIds": [...]}} — "함께 다녀왔어요 🎉" + 동행 아바타, 정책 v2).
 *
 * <p>PIN_VISIT/PIN_MEMORY 는 서버가 방문자 명의로 자동 적재하는 내부 전용 kind 다
 * ({@code GroupChatService.appendVisitCard}). 공개 {@code postMessage} 경로는 두 kind 를 계속 CHAT_KIND_INVALID 로 거부한다.</p>
 */
public enum MessageKind {
    TEXT,
    PLACE_CARDS,
    MEMO_PROMPT,
    PROCESSING,
    SYSTEM,
    REEL_LINK,
    PIN_REPLY,
    PIN_VISIT,
    PIN_MEMORY
}
