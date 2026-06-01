package com.wherewego.infrastructure.auth.apple;

/**
 * P1: Apple identityToken 검증 결과로 추출한 클레임.
 * sub = Apple 안정 사용자 식별자(oauth_id), email = private relay 가능(최초 1회만 저장).
 */
public record AppleTokenClaims(String sub, String email) { }
