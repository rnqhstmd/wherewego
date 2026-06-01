package com.wherewego.domain.auth;

/**
 * P1: Apple 네이티브 로그인 검증 입력 (FR-3).
 * identityToken/nonce 는 서명·nonce 검증에, fullName/email 은 신규 계정 최초 1회 저장에 사용된다(BR-9/12).
 * authorizationCode 는 P1 에서 수신만 한다(revoke 는 P2).
 */
public record AppleLoginCommand(
        String identityToken,
        String nonce,
        String authorizationCode,
        String givenName,
        String familyName,
        String email
) { }
