package com.wherewego.domain.pin;

/**
 * Phase 10: {@link PinService#updatePin} 의 반환 타입.
 *
 * <p>{@code summary} 는 기존 응답 본문이고, {@code wasWishOrReelToMemory} 는 WISH/REEL → MEMORY 전환
 * 1회를 나타내는 시그널이다. 정책 v2 이후 알림 fan-out 은 폐기됐고(VISIT_DETECTED 제거), 이 시그널은
 * 응답의 {@code transitionedToMemoryNow} 로 노출되어 iOS 수동 전환 confetti/메모 시트 분기에 쓰인다.</p>
 */
public record PinUpdateResult(
        PinSummary summary,
        boolean wasWishOrReelToMemory
) {
}
