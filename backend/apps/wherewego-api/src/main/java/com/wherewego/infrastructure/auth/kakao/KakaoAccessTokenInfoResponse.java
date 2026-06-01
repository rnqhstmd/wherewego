package com.wherewego.infrastructure.auth.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kakao {@code /v1/user/access_token_info} 응답.
 * P1: 네이티브 로그인 시 클라이언트가 제공한 access token 이 우리 앱({@code appId}) 발급분인지 검증한다.
 * 다른 Kakao 앱의 토큰은 {@code appId} 가 우리 설정값과 다르므로 거부한다.
 */
public record KakaoAccessTokenInfoResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("app_id") Long appId
) { }
