package com.wherewego.domain.pin;

/**
 * Phase 10: {@link PinService#updatePin} 의 반환 타입.
 *
 * <p>{@code summary} 는 기존 응답 본문이고, {@code wasWishOrReelToMemory} 는 Controller 가
 * VISIT_DETECTED 알림을 발행할지 결정하는 시그널이다 (WISH/REEL → MEMORY 전환 1회 한정).</p>
 */
public record PinUpdateResult(
        PinSummary summary,
        boolean wasWishOrReelToMemory
) {
}
