package com.wherewego.infrastructure.auth.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoUserInfoResponse(
        Long id,
        Properties properties,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {
    public record Properties(
            String nickname,
            @JsonProperty("profile_image") String profileImage,
            @JsonProperty("thumbnail_image") String thumbnailImage
    ) { }

    public record KakaoAccount(
            Profile profile
    ) {
        public record Profile(
                String nickname,
                @JsonProperty("profile_image_url") String profileImageUrl,
                @JsonProperty("thumbnail_image_url") String thumbnailImageUrl
        ) { }
    }

    public String resolvedNickname() {
        if (kakaoAccount != null && kakaoAccount.profile() != null && kakaoAccount.profile().nickname() != null) {
            return kakaoAccount.profile().nickname();
        }
        return properties != null ? properties.nickname() : null;
    }

    public String resolvedProfileImageUrl() {
        if (kakaoAccount != null && kakaoAccount.profile() != null && kakaoAccount.profile().profileImageUrl() != null) {
            return kakaoAccount.profile().profileImageUrl();
        }
        return properties != null ? properties.profileImage() : null;
    }
}
