package com.wherewego.interfaces.api.auth;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth V1 API", description = "카카오 OAuth2 + JWT 인증 API 입니다.")
public interface AuthV1ApiSpec {

    @Operation(
            summary = "카카오 로그인 URL 조회",
            description = "프론트엔드에서 사용자 리다이렉트에 사용할 카카오 인가 URL을 반환합니다."
    )
    ApiResponse<AuthV1Dto.LoginUrlResponse> getKakaoLoginUrl();

    @Operation(
            summary = "카카오 로그인 콜백",
            description = "카카오에서 발급한 인가 코드로 로그인/회원가입을 처리하고 access/refresh 쿠키를 발급합니다."
    )
    ResponseEntity<ApiResponse<AuthV1Dto.UserResponse>> kakaoCallback(AuthV1Dto.KakaoCallbackRequest request);

    @Operation(
            summary = "토큰 재발급",
            description = "refresh_token 쿠키로 access/refresh 토큰을 재발급합니다 (Rotation)."
    )
    ResponseEntity<ApiResponse<Object>> refreshToken(
            @Parameter(hidden = true) String refreshToken
    );

    @Operation(
            summary = "로그아웃",
            description = "서버의 refresh token 해시를 제거하고 access/refresh 쿠키를 만료시킵니다 (멱등)."
    )
    ResponseEntity<ApiResponse<Object>> logout(
            @Parameter(hidden = true) String accessToken
    );
}
