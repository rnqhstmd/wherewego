package com.wherewego.domain.auth;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

/**
 * P1: Apple 네이티브 로그인 검증 입력 (FR-3).
 * identityToken/nonce 는 서명·nonce 검증에, fullName/email 은 신규 계정 최초 1회 저장에 사용된다(BR-9/12).
 * authorizationCode 는 P1 에서 수신만 한다(revoke 는 P2).
 *
 * <p>도메인 자체 방어 심도: 검증에 필수인 identityToken/nonce 가 null/blank 이면 거부한다.
 * DTO {@code @NotBlank} 와 {@code AppleIdentityTokenVerifier} 진입부 가드와 별개로,
 * record 단독 생성 경로(직접 생성/다른 호출 경로)에서도 계약이 깨지지 않게 한다.
 * verifier 와 동일 코드(AUTH_APPLE_TOKEN_INVALID)로 일관되게 거부한다.
 * fullName/email/authorizationCode 는 계약상 null 허용이므로 가드하지 않는다.
 */
public record AppleLoginCommand(
        String identityToken,
        String nonce,
        String authorizationCode,
        String givenName,
        String familyName,
        String email
) {
    public AppleLoginCommand {
        if (identityToken == null || identityToken.isBlank()) {
            throw new CoreException(ErrorType.AUTH_APPLE_TOKEN_INVALID);
        }
        if (nonce == null || nonce.isBlank()) {
            throw new CoreException(ErrorType.AUTH_APPLE_TOKEN_INVALID);
        }
    }
}
