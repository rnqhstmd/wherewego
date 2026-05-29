package com.wherewego.domain.pin.cleanup;

import com.wherewego.domain.pin.PinSummary;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Phase 12 정리 후보 조회 결과 (FR-PIN-12-23).
 *
 * <p>snooze 중인 사용자: {@code totalCount=0}, {@code snoozedUntil=만료시각}, {@code items=[]}.
 * snooze 없음: {@code totalCount=N}, {@code snoozedUntil=null}, {@code items=N개 후보}.</p>
 */
public record CleanupCandidatesResult(
        int totalCount,
        ZonedDateTime snoozedUntil,
        List<PinSummary> items
) {
}
