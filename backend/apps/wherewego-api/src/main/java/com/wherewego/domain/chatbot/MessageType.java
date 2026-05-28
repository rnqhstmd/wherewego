package com.wherewego.domain.chatbot;

/**
 * Skill webhook 메시지 분류.
 *
 * 우선순위:
 *   PLACE_SELECTION
 *   > LINK_CODE
 *   > INSTAGRAM_LINK
 *   > REEL_PLACE_SELECTION   (ReelSavedSelectionSession.state = MULTI_SELECTING)
 *   > REEL_MEMO_WAITING      (state = MEMO_WAITING)
 *   > TEXT_2SEC_CANDIDATE
 *   > UNKNOWN
 *
 * <p>Phase 13: 단일 추출(1곳)도 MULTI_SELECTING 선택 단계로 통합되어 기존 SINGLE_WANT_YES/NO 는 폐기됨.
 * 1곳은 [가고 싶어요]/[그냥 저장], 2~30곳은 [전부]/[건너뛰기]·콤마 번호로 처리되며 모두
 * {@link #REEL_PLACE_SELECTION} 으로 분류된다.</p>
 */
public enum MessageType {
    LINK_CODE,
    INSTAGRAM_LINK,
    PLACE_SELECTION,
    TEXT_2SEC_CANDIDATE,
    REEL_PLACE_SELECTION,
    REEL_MEMO_WAITING,
    UNKNOWN
}
