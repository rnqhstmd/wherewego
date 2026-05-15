package com.wherewego.application.auth;

import com.wherewego.domain.user.UserModel;

public record AuthResultInfo(
        Long userId,
        String nickname,
        String profileImageUrl,
        String accessToken,
        String refreshToken
) {
    public static AuthResultInfo of(UserModel user, String accessToken, String refreshToken) {
        return new AuthResultInfo(
                user.getId(),
                user.getNickname(),
                user.getProfileImageUrl(),
                accessToken,
                refreshToken
        );
    }
}
