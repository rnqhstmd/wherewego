package com.wherewego.domain.chatbot;

/**
 * Skill webhook 메시지 분류. 우선순위: PLACE_SELECTION > LINK_CODE > INSTAGRAM_LINK > TEXT_2SEC_CANDIDATE > UNKNOWN.
 */
public enum MessageType {
    LINK_CODE,
    INSTAGRAM_LINK,
    PLACE_SELECTION,
    TEXT_2SEC_CANDIDATE,
    UNKNOWN
}
