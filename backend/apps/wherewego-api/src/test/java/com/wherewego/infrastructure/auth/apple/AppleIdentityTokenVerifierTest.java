package com.wherewego.infrastructure.auth.apple;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.wherewego.config.env.AppleAuthProperties;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1: AppleIdentityTokenVerifier 단위 검증 (FR-3/8, BR-4/5, AC-9/12/13/14, QE-2).
 * 테스트 RSA 키쌍으로 identityToken 을 자체 발급하고, WireMock 으로 JWKS({@code /auth/keys}) 를 stub 한다.
 */
class AppleIdentityTokenVerifierTest {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String AUDIENCE = "com.wherewego.app";
    private static final String JWKS_PATH = "/auth/keys";

    private static WireMockServer wireMock;
    private static RSAKey rsaJwk;
    private static RSASSASigner signer;
    private static final RefreshTokenHasher hasher = new RefreshTokenHasher();

    @BeforeAll
    static void setUp() throws JOSEException {
        rsaJwk = new RSAKeyGenerator(2048).keyID("apple-key-1").generate();
        signer = new RSASSASigner(rsaJwk);

        wireMock = new WireMockServer(0);
        wireMock.start();
        // JWKS 는 공개키만 노출 (toPublicJWK).
        String jwksJson = "{\"keys\":[" + rsaJwk.toPublicJWK().toJSONString() + "]}";
        wireMock.stubFor(get(urlPathEqualTo(JWKS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwksJson)));
    }

    @AfterAll
    static void tearDown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    private AppleIdentityTokenVerifier verifier(String jwksUrl) {
        AppleAuthProperties props = new AppleAuthProperties(AUDIENCE, ISSUER, jwksUrl, 21600L);
        return new AppleIdentityTokenVerifier(props, hasher);
    }

    private AppleIdentityTokenVerifier verifier() {
        return verifier(wireMock.baseUrl() + JWKS_PATH);
    }

    private String signToken(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private JWTClaimsSet.Builder validClaims(String rawNonce) {
        return new JWTClaimsSet.Builder()
                .subject("apple-sub-001")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .claim("email", "relay@privaterelay.appleid.com")
                .claim("nonce", hasher.sha256Hex(rawNonce));
    }

    @DisplayName("AC-9: 유효 토큰 + 올바른 nonce → sub/email 클레임을 반환한다.")
    @Test
    void validTokenWithCorrectNonce_returnsClaims() throws JOSEException {
        String rawNonce = "client-nonce-abc";
        String token = signToken(validClaims(rawNonce).build());

        AppleTokenClaims claims = verifier().verify(token, rawNonce);

        assertThat(claims.sub()).isEqualTo("apple-sub-001");
        assertThat(claims.email()).isEqualTo("relay@privaterelay.appleid.com");
    }

    @DisplayName("AC-12: aud 불일치 → 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void wrongAudience_throwsTokenInvalid() throws JOSEException {
        String rawNonce = "client-nonce-abc";
        String token = signToken(validClaims(rawNonce).audience("com.evil.other").build());

        assertThatThrownBy(() -> verifier().verify(token, rawNonce))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_APPLE_TOKEN_INVALID);
    }

    @DisplayName("AC-13: 만료된 토큰 → 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void expiredToken_throwsTokenInvalid() throws JOSEException {
        String rawNonce = "client-nonce-abc";
        String token = signToken(validClaims(rawNonce)
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());

        assertThatThrownBy(() -> verifier().verify(token, rawNonce))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_APPLE_TOKEN_INVALID);
    }

    @DisplayName("AC-14: nonce 불일치 → 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void nonceMismatch_throwsTokenInvalid() throws JOSEException {
        // 토큰에는 다른 nonce 의 해시가 들어가고, 검증 시 다른 평문 nonce 를 전달.
        String token = signToken(validClaims("token-nonce").build());

        assertThatThrownBy(() -> verifier().verify(token, "different-nonce"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_APPLE_TOKEN_INVALID);
    }

    @DisplayName("rawNonce=null → 401 AUTH_APPLE_TOKEN_INVALID (명시적 계약 가드).")
    @Test
    void nullRawNonce_throwsTokenInvalid() throws JOSEException {
        // 토큰 자체는 유효하지만 검증 입력 nonce 가 null 이면 nonce 대조가 불가능 → 즉시 거부.
        String token = signToken(validClaims("client-nonce-abc").build());

        assertThatThrownBy(() -> verifier().verify(token, null))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_APPLE_TOKEN_INVALID);
    }

    @DisplayName("nonce 클레임이 없는 토큰 → 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void missingNonceClaim_throwsTokenInvalid() throws JOSEException {
        JWTClaimsSet noNonce = new JWTClaimsSet.Builder()
                .subject("apple-sub-001")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .build();
        String token = signToken(noNonce);

        assertThatThrownBy(() -> verifier().verify(token, "any-nonce"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_APPLE_TOKEN_INVALID);
    }

    @DisplayName("서명이 다른 키로 위조된 토큰 → 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void wrongSignature_throwsTokenInvalid() throws JOSEException {
        String rawNonce = "client-nonce-abc";
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("apple-key-1").generate();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("apple-key-1").build(),
                validClaims(rawNonce).build());
        forged.sign(new RSASSASigner(otherKey));

        assertThatThrownBy(() -> verifier().verify(forged.serialize(), rawNonce))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_APPLE_TOKEN_INVALID);
    }

    @DisplayName("QE-2: JWKS 네트워크 오류 → 502 AUTH_APPLE_JWKS_UNAVAILABLE (검증실패와 구분).")
    @Test
    void jwksUnavailable_throwsJwksUnavailable() throws JOSEException {
        String rawNonce = "client-nonce-abc";
        String token = signToken(validClaims(rawNonce).build());
        // 닫힌 포트로 향하는 JWKS URL → RemoteKeySourceException.
        AppleIdentityTokenVerifier brokenVerifier = verifier("http://127.0.0.1:1/auth/keys");

        assertThatThrownBy(() -> brokenVerifier.verify(token, rawNonce))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.AUTH_APPLE_JWKS_UNAVAILABLE);
    }
}
