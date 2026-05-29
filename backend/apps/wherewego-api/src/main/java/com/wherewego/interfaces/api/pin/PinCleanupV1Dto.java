package com.wherewego.interfaces.api.pin;

import com.wherewego.domain.pin.PinSummary;
import com.wherewego.domain.pin.cleanup.CleanupCandidatesResult;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Phase 12 오래된 핀 정리 API DTO (FR-PIN-12-23, 24).
 */
public final class PinCleanupV1Dto {

    /**
     * 정리 후보 조회 응답 (FR-PIN-12-23).
     *
     * @param totalCount   정리 대상 핀 개수 (snooze 중이면 0)
     * @param snoozedUntil 사용자의 snooze 만료 시각. snooze 없음이면 null.
     * @param items        정리 대상 핀 상세 (snooze 중이면 빈 배열)
     */
    public record CleanupCandidatesResponse(
            int totalCount,
            ZonedDateTime snoozedUntil,
            List<PinV1Dto.PinSummaryResponse> items
    ) {
        public static CleanupCandidatesResponse from(CleanupCandidatesResult r) {
            List<PinV1Dto.PinSummaryResponse> items = r.items().stream()
                    .map(PinCleanupV1Dto::toSummaryResponse)
                    .toList();
            return new CleanupCandidatesResponse(r.totalCount(), r.snoozedUntil(), items);
        }
    }

    /**
     * 정리 실행 응답 (FR-PIN-12-24).
     *
     * @param deletedCount 이번 호출이 실제로 삭제한 핀 수 (이미 삭제됐던 행 제외)
     */
    public record CleanupExecuteResponse(int deletedCount) {
        public static CleanupExecuteResponse of(int deletedCount) {
            return new CleanupExecuteResponse(deletedCount);
        }
    }

    private static PinV1Dto.PinSummaryResponse toSummaryResponse(PinSummary s) {
        return PinV1Dto.PinSummaryResponse.from(s);
    }

    private PinCleanupV1Dto() {
    }
}
