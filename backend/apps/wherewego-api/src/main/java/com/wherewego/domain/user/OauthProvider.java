package com.wherewego.domain.user;

/**
 * P1: OAuth 공급자. 기존 단일 Kakao 식별 구조를 (provider, oauthId) 로 일반화한다.
 * Kakao 는 oauthId = kakao_user_id::text, Apple 은 oauthId = identityToken sub.
 */
public enum OauthProvider {
    KAKAO,
    APPLE
}
