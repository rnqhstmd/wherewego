package com.wherewego.domain.chatbot;

/**
 * Skill webhook 메시지 분류.
 *
 * 우선순위 (Phase 12):
 *   PLACE_SELECTION
 *   > LINK_CODE
 *   > INSTAGRAM_LINK
 *   > SINGLE_WANT_YES / SINGLE_WANT_NO         (ReelSavedSelectionSession.state = SINGLE_WANT)
 *   > REEL_PLACE_SELECTION                     (state = MULTI_SELECTING / BULK_SAVE)
 *   > REEL_MEMO_WAITING                        (state = MEMO_WAITING)
 *   > TEXT_2SEC_CANDIDATE
 *   > UNKNOWN
 *
 * Phase 12 신규 enum:
 * <ul>
 *   <li>{@link #REEL_PLACE_SELECTION} — MULTI/BULK 단계에서 콤마 숫자/"전부"/"건너뛰기" 발화</li>
 *   <li>{@link #SINGLE_WANT_YES}      — SINGLE_WANT 단계 "가고 싶어요" QuickReply</li>
 *   <li>{@link #SINGLE_WANT_NO}       — SINGLE_WANT 단계 "발견으로만 저장" QuickReply</li>
 *   <li>{@link #REEL_MEMO_WAITING}    — MEMO_WAITING 단계 메모 입력 발화</li>
 * </ul>
 *
 * <p>Phase 11 legacy {@code INSTAGRAM_PENDING_MEMO} / {@code PendingInstagramSession} 은
 * Phase 12 에서 {@link com.wherewego.domain.chatbot.ReelSavedSelectionSession} 으로 전면 대체되어 폐기됨.</p>
 */
public enum MessageType {
    LINK_CODE,
    INSTAGRAM_LINK,
    PLACE_SELECTION,
    TEXT_2SEC_CANDIDATE,
    REEL_PLACE_SELECTION,
    SINGLE_WANT_YES,
    SINGLE_WANT_NO,
    REEL_MEMO_WAITING,
    UNKNOWN
}
