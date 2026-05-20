package com.wherewego.interfaces.api.user;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "User V1 API", description = "사용자 프로필 조회/수정 API 입니다.")
public interface UserV1ApiSpec {

    @Operation(
            summary = "내 정보 조회",
            description = "현재 로그인 사용자의 프로필 정보를 반환합니다."
    )
    ApiResponse<UserV1Dto.UserResponse> getCurrentUser(
            @Parameter(hidden = true) Long userId
    );

    @Operation(
            summary = "닉네임 변경",
            description = "현재 로그인 사용자의 닉네임을 변경합니다. 한글/영문/숫자 2~12자만 허용됩니다."
    )
    ApiResponse<UserV1Dto.UserResponse> updateNickname(
            @Parameter(hidden = true) Long userId,
            UserV1Dto.UpdateNicknameRequest request
    );
}
