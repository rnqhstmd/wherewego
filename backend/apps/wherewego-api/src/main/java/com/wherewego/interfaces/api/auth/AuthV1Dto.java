package com.wherewego.interfaces.api.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.application.auth.KakaoLoginUrlInfo;
import com.wherewego.domain.auth.AppleLoginCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public class AuthV1Dto {

    public record KakaoCallbackRequest(@NotBlank String code) { }

    public record KakaoNativeLoginRequest(@NotBlank String kakaoAccessToken) { }

    /**
     * Apple 네이티브 로그인 요청.
     * nonce 계약: 클라이언트가 평문 nonce 를 전송하고, 서버가 SHA-256 소문자 hex 로 변환해
     * identityToken 의 nonce 클레임과 대조한다(BR-5).
     * authorizationCode 는 P1 에서 수신만(revoke 는 P2). fullName/email 은 최초 1회만 저장(BR-9).
     */
    public record AppleNativeLoginRequest(
            @NotBlank String identityToken,
            @NotBlank String nonce,
            String authorizationCode,
            @Valid AppleFullName fullName,
            String email
    ) {
        public AppleLoginCommand toCommand() {
            String givenName = fullName != null ? fullName.givenName() : null;
            String familyName = fullName != null ? fullName.familyName() : null;
            return new AppleLoginCommand(identityToken, nonce, authorizationCode, givenName, familyName, email);
        }
    }

    public record AppleFullName(String givenName, String familyName) { }

    public record RefreshRequest(@NotBlank String refreshToken) { }

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
        public static TokenResponse of(AuthResultInfo info, long expiresIn) {
            return new TokenResponse(info.accessToken(), info.refreshToken(), expiresIn);
        }
    }

    public record LoginUrlResponse(String loginUrl) {
        public static LoginUrlResponse from(KakaoLoginUrlInfo info) {
            return new LoginUrlResponse(info.loginUrl());
        }
    }

    public record UserResponse(Long id, String nickname, String profileImageUrl) {
        public static UserResponse from(AuthResultInfo info) {
            return new UserResponse(info.userId(), info.nickname(), info.profileImageUrl());
        }
    }

    private AuthV1Dto() { }
}
