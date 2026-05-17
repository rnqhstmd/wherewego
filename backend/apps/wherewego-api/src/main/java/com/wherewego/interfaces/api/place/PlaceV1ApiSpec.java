package com.wherewego.interfaces.api.place;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Place V1 API", description = "장소 검색 API 입니다 (Phase 6 FR-API-2).")
public interface PlaceV1ApiSpec {

    @Operation(
            summary = "장소 키워드 검색",
            description = "인증된 사용자가 키워드로 장소를 검색합니다. " +
                    "카카오 Local 우선 호출 후 0건/실패 시 Google Places 동기 폴백을 수행합니다. " +
                    "둘 다 실패하거나 0건이면 200 OK + 빈 items 배열을 반환합니다 (502 던지지 않음). " +
                    "검색어가 비어있으면 PLACE_SEARCH_KEYWORD_INVALID (400) 으로 거부됩니다."
    )
    ApiResponse<PlaceV1Dto.PlaceSearchResponse> search(
            @Parameter(hidden = true) Long userId,
            String keyword
    );
}
