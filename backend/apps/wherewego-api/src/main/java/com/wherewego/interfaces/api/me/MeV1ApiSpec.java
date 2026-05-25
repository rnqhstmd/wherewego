package com.wherewego.interfaces.api.me;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Me V1 API", description = "현재 로그인 사용자 보조 API (Phase 11 PR-B).")
public interface MeV1ApiSpec {

    @Operation(
            summary = "온보딩 상태 조회",
            description = "활성 그룹 보유 / 활성 그룹 멤버 수 / 봇 매핑 보유 여부를 한 번에 반환합니다. " +
                    "사용자별 Caffeine 캐시 TTL 60초."
    )
    ApiResponse<MeV1Dto.OnboardingStatusResponse> getOnboardingStatus(
            @Parameter(hidden = true) Long userId
    );
}
