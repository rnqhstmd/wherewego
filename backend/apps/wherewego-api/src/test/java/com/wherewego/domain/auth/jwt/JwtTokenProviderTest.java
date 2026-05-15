package com.wherewego.domain.auth.jwt;

import com.wherewego.config.env.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-must-be-at-least-32-characters-long!!";

    private final JwtProperties props = new JwtProperties(SECRET, 3600L, 1209600L);
    private final JwtTokenProvider provider = new JwtTokenProvider(props);

    @DisplayName("Access Token을 발급할 때,")
    @Nested
    class IssueAccessToken {
        @DisplayName("발급 후 parseAccessToken에 넣으면, Valid + 동일한 userId를 반환한다.")
        @Test
        void issueAccessToken_andParseAccessToken_returnsValidUserId() {
            // arrange
            Long userId = 42L;

            // act
            String token = provider.issueAccessToken(userId);
            JwtValidationResult result = provider.parseAccessToken(token);

            // assert
            assertThat(result).isInstanceOf(JwtValidationResult.Valid.class);
            assertThat(((JwtValidationResult.Valid) result).userId()).isEqualTo(userId);
        }
    }

    @DisplayName("Refresh Token을 발급할 때,")
    @Nested
    class IssueRefreshToken {
        @DisplayName("발급 후 parseRefreshToken에 넣으면, Valid + 동일한 userId를 반환한다.")
        @Test
        void issueRefreshToken_andParseRefreshToken_returnsValidUserId() {
            // arrange
            Long userId = 99L;

            // act
            String token = provider.issueRefreshToken(userId);
            JwtValidationResult result = provider.parseRefreshToken(token);

            // assert
            assertThat(result).isInstanceOf(JwtValidationResult.Valid.class);
            assertThat(((JwtValidationResult.Valid) result).userId()).isEqualTo(userId);
        }
    }

    @DisplayName("토큰 타입을 검증할 때,")
    @Nested
    class TypClaim {
        @DisplayName("refresh 토큰을 parseAccessToken에 넣으면, INVALID_TYPE을 반환한다.")
        @Test
        void parseAccessToken_withRefreshToken_returnsInvalidType() {
            // arrange
            String refresh = provider.issueRefreshToken(1L);

            // act
            JwtValidationResult result = provider.parseAccessToken(refresh);

            // assert
            assertThat(result).isEqualTo(JwtValidationResult.Invalid.INVALID_TYPE);
        }

        @DisplayName("access 토큰을 parseRefreshToken에 넣으면, INVALID_TYPE을 반환한다.")
        @Test
        void parseRefreshToken_withAccessToken_returnsInvalidType() {
            // arrange
            String access = provider.issueAccessToken(1L);

            // act
            JwtValidationResult result = provider.parseRefreshToken(access);

            // assert
            assertThat(result).isEqualTo(JwtValidationResult.Invalid.INVALID_TYPE);
        }
    }

    @DisplayName("잘못된 토큰을 파싱할 때,")
    @Nested
    class InvalidToken {
        @DisplayName("형식이 잘못된 토큰을 넣으면, MALFORMED을 반환한다.")
        @Test
        void parseAccessToken_withMalformedToken_returnsMalformed() {
            // act
            JwtValidationResult result = provider.parseAccessToken("garbage");

            // assert
            assertThat(result).isEqualTo(JwtValidationResult.Invalid.MALFORMED);
        }

        @DisplayName("서명이 변조된 토큰을 넣으면, INVALID_SIGNATURE를 반환한다.")
        @Test
        void parseAccessToken_withTamperedSignature_returnsInvalidSignature() {
            // arrange
            String token = provider.issueAccessToken(1L);
            // 마지막 4자(서명부 일부) 변조
            String tampered = token.substring(0, token.length() - 4) + "AAAA";

            // act
            JwtValidationResult result = provider.parseAccessToken(tampered);

            // assert
            assertThat(result).isEqualTo(JwtValidationResult.Invalid.INVALID_SIGNATURE);
        }

        @DisplayName("이미 만료된 토큰을 넣으면, EXPIRED를 반환한다.")
        @Test
        void parseAccessToken_withExpiredToken_returnsExpired() {
            // arrange: jjwt builder로 즉시 만료된 토큰 생성 (iat/exp가 과거)
            SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            String expiredToken = Jwts.builder()
                    .subject("1")
                    .claim("typ", "access")
                    .issuedAt(new Date(now - 10_000))
                    .expiration(new Date(now - 1_000))
                    .signWith(key)
                    .compact();

            // act
            JwtValidationResult result = provider.parseAccessToken(expiredToken);

            // assert
            assertThat(result).isEqualTo(JwtValidationResult.Invalid.EXPIRED);
        }
    }
}
