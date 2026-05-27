package com.wherewego.interfaces.api.user;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "User Cleanup Snooze V1 API", description = "Phase 12 오래된 핀 정리 배너 snooze API 입니다.")
public interface UserCleanupSnoozeV1ApiSpec {

    @Operation(
            summary = "정리 배너 7일 snooze",
            description = "Phase 12 (FR-PIN-12-25): 현재 사용자의 cleanup_snoozed_until 을 NOW()+7일로 갱신합니다. " +
                    "기존 snooze 가 있어도 덮어씁니다 (재snooze 가능)."
    )
    ApiResponse<UserCleanupSnoozeV1Dto.CleanupSnoozeResponse> snooze(
            @Parameter(hidden = true) Long userId
    );
}
