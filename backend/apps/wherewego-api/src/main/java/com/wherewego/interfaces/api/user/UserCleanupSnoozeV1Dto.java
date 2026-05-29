package com.wherewego.interfaces.api.user;

import java.time.ZonedDateTime;

/**
 * Phase 12: 오래된 핀 정리 배너 snooze API DTO (FR-PIN-12-25).
 */
public final class UserCleanupSnoozeV1Dto {

    /**
     * snooze 응답.
     *
     * @param snoozedUntil 갱신된 cleanup_snoozed_until (NOW()+7일)
     */
    public record CleanupSnoozeResponse(ZonedDateTime snoozedUntil) {
        public static CleanupSnoozeResponse of(ZonedDateTime snoozedUntil) {
            return new CleanupSnoozeResponse(snoozedUntil);
        }
    }

    private UserCleanupSnoozeV1Dto() {
    }
}
