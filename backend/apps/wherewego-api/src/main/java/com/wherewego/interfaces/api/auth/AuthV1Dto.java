package com.wherewego.interfaces.api.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.application.auth.KakaoLoginUrlInfo;
import jakarta.validation.constraints.NotBlank;

public class AuthV1Dto {

    public record KakaoCallbackRequest(@NotBlank String code) { }

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
