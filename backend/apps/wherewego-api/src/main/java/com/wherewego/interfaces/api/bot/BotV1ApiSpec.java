package com.wherewego.interfaces.api.bot;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Bot V1 API", description = "카카오톡 챗봇 연동코드 발급 API 입니다.")
public interface BotV1ApiSpec {

    @Operation(
            summary = "챗봇 연동코드 발급",
            description = "현재 로그인 사용자에게 6자리 카카오톡 챗봇 연동코드를 발급합니다. " +
                    "재발급 시 기존 활성 코드는 만료 처리되고 새 코드가 발급됩니다."
    )
    ApiResponse<BotV1Dto.LinkCodeResponse> issueLinkCode(
            @Parameter(hidden = true) Long userId
    );
}
