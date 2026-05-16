package com.wherewego.interfaces.api.pin;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Pin V1 API", description = "그룹 핀 목록/수정/삭제 API 입니다 (Phase 4).")
public interface PinV1ApiSpec {

    @Operation(
            summary = "그룹 핀 목록 조회",
            description = "활성 그룹원이 자신의 그룹에 속한 핀 목록을 created_at 내림차순으로 반환합니다 (FR-1, BR-10). " +
                    "tag 쿼리 파라미터로 PLACE/MEMORY 필터링이 가능합니다 (FR-5). " +
                    "잘못된 tag 값은 PIN_TAG_INVALID (400) 으로 거부됩니다 (AC-2 일관성). " +
                    "deleted_at IS NULL 인 행만 반환합니다 (BR-2)."
    )
    ApiResponse<PinV1Dto.PinListResponse> listPins(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            String tag
    );

    @Operation(
            summary = "핀 부분 수정",
            description = "활성 그룹원이 핀의 memo/tag 를 부분 수정합니다 (FR-2, BR-7). " +
                    "memo 가 빈 문자열이면 잠금 해제(BR-8), 비어있지 않으면 MANUAL 마킹(BR-3, FR-4). " +
                    "키 없음 vs JSON null vs 빈 문자열을 구분하기 위해 본문은 JsonNode 로 받습니다."
    )
    ApiResponse<PinV1Dto.PinSummaryResponse> updatePin(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long pinId,
            PinV1Dto.UpdatePinRequest request
    );

    @Operation(
            summary = "핀 소프트 삭제",
            description = "활성 그룹원이 핀을 소프트 삭제합니다 (FR-3, BR-2). " +
                    "이미 삭제된 핀은 PIN_NOT_FOUND 로 거부됩니다 (BR-6). " +
                    "성공 시 204 No Content 를 반환합니다."
    )
    void deletePin(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long pinId
    );
}
