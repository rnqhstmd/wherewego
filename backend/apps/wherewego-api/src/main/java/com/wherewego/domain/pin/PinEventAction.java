package com.wherewego.domain.pin;

/**
 * 핀 이벤트 액션 종류. V012 {@code pin_events.action} CHECK 제약과 1:1 매핑된다.
 *
 * <p>Phase 12 P0 범위: {@link #WANT} 단일값만 사용한다.</p>
 *
 * <p>후속 Phase 12.2 에서 {@code VIEW}, {@code SHARE}, {@code ROULETTE_SELECTED} 가 추가될 예정이며
 * (D-8, D-17), 추가 시 V0xx 마이그레이션으로 {@code chk_pin_events_action} CHECK 제약을 ALTER 한다.</p>
 */
public enum PinEventAction {
    WANT
}
