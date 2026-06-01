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
            summary = "카카오 네이티브 로그인 (앱)",
            description = "iOS/Android 앱이 Kakao SDK access token 으로 로그인합니다. "
                    + "서버가 /v2/user/me 로 토큰을 검증해 (KAKAO, kakaoId) find-or-create 후 우리 JWT 를 발급합니다. "
                    + "Set-Cookie 를 설정하지 않고 응답 본문(accessToken/refreshToken/expiresIn)으로 전달합니다."
    )
    ApiResponse<AuthV1Dto.TokenResponse> kakaoNativeLogin(AuthV1Dto.KakaoNativeLoginRequest request);

    @Operation(
            summary = "Apple 네이티브 로그인 (앱)",
            description = "앱이 Apple identityToken 으로 로그인합니다. 서버가 JWKS 서명·iss·aud·exp·nonce 를 검증해 "
                    + "(APPLE, sub) find-or-create 후 우리 JWT 를 발급합니다. Set-Cookie 미설정. "
                    + "nonce 계약: 클라이언트는 평문 nonce 를 전송하고, 서버는 이를 SHA-256 소문자 hex 로 변환해 "
                    + "identityToken 의 nonce 클레임과 대조합니다. fullName/email 은 신규 계정 최초 1회만 저장됩니다."
    )
    ApiResponse<AuthV1Dto.TokenResponse> appleNativeLogin(AuthV1Dto.AppleNativeLoginRequest request);

    @Operation(
            summary = "토큰 재발급 (앱, body)",
            description = "요청 body 의 refreshToken 으로 access/refresh 토큰을 재발급합니다 (Rotation). "
                    + "기존 /api/v1/auth/token/refresh(쿠키)와 병행 — Set-Cookie 미설정, 본문으로 전달합니다."
    )
    ApiResponse<AuthV1Dto.TokenResponse> refresh(AuthV1Dto.RefreshRequest request);

    @Operation(
            summary = "로그아웃",
            description = "서버의 refresh token 해시를 제거하고 access/refresh 쿠키를 만료시킵니다 (멱등)."
    )
    ResponseEntity<ApiResponse<Object>> logout(
            @Parameter(hidden = true) String accessToken
    );
}
