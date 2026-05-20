package com.wherewego.domain.chatbot;

/**
 * Skill webhook 메시지 분류.
 *
 * 우선순위:
 *   PLACE_SELECTION > LINK_CODE > INSTAGRAM_PENDING_MEMO > INSTAGRAM_LINK > TEXT_2SEC_CANDIDATE > UNKNOWN
 *
 * INSTAGRAM_PENDING_MEMO 는 직전에 인스타 URL을 보낸 사용자의 다음 메시지를 가리킨다.
 * (PendingInstagramSession에 해당 botUserKey 가 존재하는 경우 적용)
 */
public enum MessageType {
    LINK_CODE,
    INSTAGRAM_LINK,
    INSTAGRAM_PENDING_MEMO,
    PLACE_SELECTION,
    TEXT_2SEC_CANDIDATE,
    UNKNOWN
}
