package com.wherewego.domain.pin;

/**
 * Pin 의미 구분. V006 이후 pins.tag CHECK (REEL, WISH, MEMORY).
 * <ul>
 *     <li>REEL  : 카카오톡 챗봇 경로로 등록된 핀 (자동 추출 + 후보 카드 선택 둘 다 포함)</li>
 *     <li>WISH  : 웹 직접 등록 — 가보고 싶은 곳 (설렘)</li>
 *     <li>MEMORY: 웹 직접 등록 — 다녀온 의미 있는 곳 (추억)</li>
 * </ul>
 *
 * <p>REEL 정책: UI(MemoTagPanelContent)에서만 웹 등록 제한. 백엔드 API는 enum 검증만.</p>
 */
public enum PinTag {
    REEL,
    WISH,
    MEMORY
}
