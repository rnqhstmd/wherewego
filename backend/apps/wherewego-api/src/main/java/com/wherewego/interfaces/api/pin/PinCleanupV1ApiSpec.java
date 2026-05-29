package com.wherewego.interfaces.api.pin;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Pin Cleanup V1 API", description = "Phase 12 오래된 핀 정리 API 입니다.")
public interface PinCleanupV1ApiSpec {

    @Operation(
            summary = "정리 후보 핀 조회",
            description = "Phase 12 (FR-PIN-12-23): 활성 그룹원이 정리 대상 핀 (REEL + AUTO + want_count=0 + " +
                    "created_at < NOW()-30일) 목록을 조회합니다. 사용자가 snooze 중이면 totalCount=0 + " +
                    "snoozedUntil 만 반환합니다. 비활성 멤버는 GROUP_NOT_MEMBER (403)."
    )
    ApiResponse<PinCleanupV1Dto.CleanupCandidatesResponse> listCandidates(
            @Parameter(hidden = true) Long userId,
            Long groupId
    );

    @Operation(
            summary = "정리 대상 핀 일괄 삭제",
            description = "Phase 12 (FR-PIN-12-24): 활성 그룹원이 정리 대상 핀을 일괄 soft-delete 합니다. " +
                    "트랜잭션 내에서 후보 ID 를 재계산하여 race-safe 합니다. " +
                    "비활성 멤버는 GROUP_NOT_MEMBER (403)."
    )
    ApiResponse<PinCleanupV1Dto.CleanupExecuteResponse> executeBulk(
            @Parameter(hidden = true) Long userId,
            Long groupId
    );
}
