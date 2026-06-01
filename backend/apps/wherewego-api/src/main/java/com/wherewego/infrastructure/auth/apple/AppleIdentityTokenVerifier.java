package com.wherewego.infrastructure.auth.apple;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.wherewego.config.env.AppleAuthProperties;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.Set;

/**
 * P1: Apple identityToken 검증 (FR-3/8/9, BR-4/5).
 *
 * <p>nimbus-jose-jwt 의 {@link JWKSource} 가 Apple JWKS({@code /auth/keys}) 를
 * 캐싱·TTL 만료 재조회·키 로테이션(kid 미스 시 재조회) 까지 내장 처리한다(FR-8).
 *
 * <p>검증 항목:
 * <ul>
 *     <li>RS256 서명 (JWKS 공개키)</li>
 *     <li>iss = {@code https://appleid.apple.com}, aud = 앱 번들 ID (exact match)</li>
 *     <li>exp (만료) — DefaultJWTClaimsVerifier 가 검증</li>
 *     <li>nonce — 클라 평문의 SHA-256 소문자 hex 가 토큰 nonce 클레임과 일치 (BR-5)</li>
 * </ul>
 *
 * <p>예외 분기:
 * <ul>
 *     <li>{@link RemoteKeySourceException} (JWKS 네트워크 오류) → 502 AUTH_APPLE_JWKS_UNAVAILABLE (QE-2)</li>
 *     <li>서명/iss/aud/exp/형식 오류, nonce 불일치 → 401 AUTH_APPLE_TOKEN_INVALID (AC-12/13/14)</li>
 * </ul>
 */
@Component
public class AppleIdentityTokenVerifier {

    private static final String NONCE_CLAIM = "nonce";
    /** JWKS 갱신 시 캐시 refresh 타임아웃 (캐시 TTL 보다 충분히 작게). */
    private static final long JWKS_HTTP_TIMEOUT_MS = 15_000L;
    /** JWKS HTTP 조회 connect/read 타임아웃 (ms). */
    private static final int JWKS_CONNECT_TIMEOUT_MS = 3_000;
    private static final int JWKS_READ_TIMEOUT_MS = 3_000;

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
    private final RefreshTokenHasher refreshTokenHasher;

    public AppleIdentityTokenVerifier(AppleAuthProperties props, RefreshTokenHasher refreshTokenHasher) {
        this.refreshTokenHasher = refreshTokenHasher;

        URL jwksUrl;
        try {
            jwksUrl = URI.create(props.jwksUrl()).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("apple.jwks-url 이 올바른 URL 이 아닙니다: " + props.jwksUrl(), e);
        }

        // 캐싱·로테이션 내장 (FR-8). 첫째 인자=캐시 TTL(만료 시 재조회),
        // 둘째 인자=갱신 시 JWKS HTTP 조회 타임아웃. 후자는 TTL 보다 충분히 작아야 한다
        // (nimbus refresh-ahead 시간과 합산이 TTL 을 넘으면 안 됨).
        //
        // outageTolerant 미사용 (보안): JWKS 조회 실패 시 마지막 캐시 키를 재사용하면, Apple 이
        //   키를 긴급 폐기한 직후에도 폐기 키로 서명된 위조/탈취 토큰을 최대 TTL 동안 통과시킬 수 있다.
        //   대신 JWKS 조회 실패는 RemoteKeySourceException → AUTH_APPLE_JWKS_UNAVAILABLE(502) 로 노출해
        //   클라이언트가 재시도 판단을 하게 한다(QE-2). 정상 서명 불일치는 키 조회 성공 후 401 로 분기된다.
        ResourceRetriever retriever = new DefaultResourceRetriever(JWKS_CONNECT_TIMEOUT_MS, JWKS_READ_TIMEOUT_MS);
        JWKSource<SecurityContext> jwkSource = JWKSourceBuilder.create(jwksUrl, retriever)
                .cache(props.jwksTtlSeconds() * 1000L, JWKS_HTTP_TIMEOUT_MS)
                .rateLimited(false) // 키 로테이션 의심 시 forced-refresh 가 rate-limit 대기로 지연되지 않게 한다.
                .build();

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder()
                        .issuer(props.issuer())
                        .audience(props.audience())
                        .build(),
                Set.of("sub", "exp")
        ));
        this.jwtProcessor = processor;
    }

    /**
     * identityToken 을 검증하고 클레임을 반환한다. 실패 시 CoreException(401/502).
     *
     * @param rawNonce 클라이언트가 평문으로 전송한 nonce. 서버가 SHA-256 소문자 hex 로 변환해
     *                 토큰 nonce 클레임과 대조한다.
     */
    public AppleTokenClaims verify(String identityToken, String rawNonce) {
        // 명시적 계약: rawNonce 는 필수다(BR-5). 비면 nonce 대조가 무의미하므로 즉시 거부한다.
        // 호출부(AppleNativeLoginRequest.nonce @NotBlank)에서 1차 차단되지만, verifier 단독 사용/
        // 다른 호출 경로에서도 계약이 깨지지 않도록 여기서도 방어한다.
        if (rawNonce == null || rawNonce.isBlank()) {
            throw new CoreException(ErrorType.AUTH_APPLE_TOKEN_INVALID);
        }
        try {
            JWTClaimsSet claims = jwtProcessor.process(identityToken, null); // 서명 + iss + aud + exp (BR-4)

            String expectedNonce = refreshTokenHasher.sha256Hex(rawNonce);
            String actualNonce = claims.getStringClaim(NONCE_CLAIM);
            if (!expectedNonce.equals(actualNonce)) { // nonce 없거나 불일치 (BR-5, AC-14)
                throw new CoreException(ErrorType.AUTH_APPLE_TOKEN_INVALID);
            }

            return new AppleTokenClaims(claims.getSubject(), claims.getStringClaim("email"));
        } catch (RemoteKeySourceException e) { // JWKS 조회 실패 (QE-2) — 검증실패와 구분
            throw new CoreException(ErrorType.AUTH_APPLE_JWKS_UNAVAILABLE);
        } catch (BadJOSEException | JOSEException | ParseException e) { // 서명/iss/aud/exp/형식 (AC-12/13)
            // nimbus 는 JWKS 조회 실패를 키 셀렉터 안에서 KeySourceException(RemoteKeySourceException)으로
            // 감싸 "no matching key" 형태의 BadJOSEException 으로 전파하기도 한다.
            // 원인 체인에 KeySourceException 이 있으면 네트워크 장애로 보고 502 로 구분(QE-2).
            if (hasKeySourceCause(e)) {
                throw new CoreException(ErrorType.AUTH_APPLE_JWKS_UNAVAILABLE);
            }
            throw new CoreException(ErrorType.AUTH_APPLE_TOKEN_INVALID);
        }
    }

    private boolean hasKeySourceCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof KeySourceException) {
                return true;
            }
        }
        return false;
    }
}
