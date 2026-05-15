package com.wherewego.domain.pin;

/**
 * 메모 출처. V001 {@code pins.memo_source CHECK (AUTO, MANUAL)} 또는 NULL.
 * <ul>
 *     <li>{@link #AUTO} : 챗봇 2초 룰 자동 부착</li>
 *     <li>{@link #MANUAL} : 웹에서 사용자가 직접 입력 (수동 우선)</li>
 * </ul>
 */
public enum MemoSource {
    AUTO,
    MANUAL
}
