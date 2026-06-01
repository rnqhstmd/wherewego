package com.wherewego.interfaces.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.wherewego.config.env.JwtProperties;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.user.OauthProvider;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class AuthV1ControllerIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String APPLE_AUDIENCE = "com.wherewego.app";
    private static final String APPLE_JWKS_PATH = "/auth/keys";

    // Apple identityToken 자체 발급용 RSA 키쌍 (JWKS 도 이 공개키로 stub).
    private static final RSAKey APPLE_RSA_JWK = generateAppleKey();
    private static final RefreshTokenHasher NONCE_HASHER = new RefreshTokenHasher();

    private static RSAKey generateAppleKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("apple-key-1").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void overrideExternalUrls(DynamicPropertyRegistry registry) {
        registry.add("kakao.oauth.token-base-url", wireMock::baseUrl);
        registry.add("kakao.oauth.user-base-url", wireMock::baseUrl);
        // Apple JWKS 를 WireMock 으로 override (AC-9~15).
        registry.add("apple.jwks-url", () -> wireMock.baseUrl() + APPLE_JWKS_PATH);
    }


    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtProperties jwtProperties;

    private static final long KAKAO_USER_ID = 9876543210L;
    // test application.yml 의 kakao.oauth.app-id 와 일치해야 한다(앱 귀속 검증).
    private static final long KAKAO_APP_ID = 123456L;

    @BeforeEach
    void cleanUp() {
        userJpaRepository.deleteAll();
        wireMock.resetAll();
        stubAppleJwks();
    }

    // ------------------------------------------------------------------
    // Apple JWKS / identityToken 헬퍼
    // ------------------------------------------------------------------

    private void stubAppleJwks() {
        String jwksJson = "{\"keys\":[" + APPLE_RSA_JWK.toPublicJWK().toJSONString() + "]}";
        wireMock.stubFor(get(urlPathEqualTo(APPLE_JWKS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwksJson)));
    }

    private String issueAppleToken(String sub, String rawNonce, String audience, long expEpochMillis, String email) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .issuer(APPLE_ISSUER)
                    .audience(audience)
                    .expirationTime(new Date(expEpochMillis))
                    .claim("nonce", NONCE_HASHER.sha256Hex(rawNonce));
            if (email != null) {
                claims.claim("email", email);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(APPLE_RSA_JWK.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(APPLE_RSA_JWK));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<JsonNode> callKakaoNative(String kakaoAccessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                "{\"kakaoAccessToken\":\"" + kakaoAccessToken + "\"}", headers);
        return restTemplate.exchange("/api/v1/auth/kakao/native", HttpMethod.POST, entity, JsonNode.class);
    }

    private ResponseEntity<JsonNode> callAppleNative(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/auth/apple/native", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void stubKakaoSuccess(String nickname, String profileImageUrl) {
        wireMock.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "kakao-access",
                                  "token_type": "bearer",
                                  "expires_in": 3600,
                                  "refresh_token": "kakao-refresh",
                                  "scope": "profile"
                                }
                                """)));

        String userJson = """
                {
                  "id": %d,
                  "properties": {
                    "nickname": "%s",
                    "profile_image": "%s"
                  }
                }
                """.formatted(KAKAO_USER_ID, nickname, profileImageUrl);

        wireMock.stubFor(get(urlEqualTo("/v2/user/me"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(userJson)));
    }

    private ResponseEntity<JsonNode> callKakaoCallback(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{\"code\":\"" + code + "\"}", headers);
        return restTemplate.exchange("/api/v1/auth/kakao/callback", HttpMethod.POST, entity, JsonNode.class);
    }

    private List<String> setCookieHeaders(ResponseEntity<?> response) {
        List<String> headers = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        return headers != null ? headers : List.of();
    }

    private Optional<String> findCookie(List<String> cookies, String name) {
        return cookies.stream().filter(c -> c.startsWith(name + "=")).findFirst();
    }

    private String extractCookieValue(String setCookieHeader, String name) {
        // 예: "access_token=abc.def.ghi; Path=/; HttpOnly; Max-Age=3600"
        int eq = setCookieHeader.indexOf('=');
        int semi = setCookieHeader.indexOf(';');
        if (semi < 0) semi = setCookieHeader.length();
        return setCookieHeader.substring(eq + 1, semi);
    }

    @DisplayName("POST /api/v1/auth/kakao/callback - 신규 사용자면 쿠키 2건과 함께 200을 반환하고 users 테이블에 저장한다 (AC-1, AC-2, AC-11, AC-12).")
    @Test
    void kakaoCallback_newUser_setsBothCookiesWithCorrectMaxAge() {
        // arrange
        stubKakaoSuccess("새닉네임", "http://img.example/p.png");

        // act
        ResponseEntity<JsonNode> response = callKakaoCallback("test-code");

        // assert: 200
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // assert: Set-Cookie 2건 + Max-Age + HttpOnly + Path
        List<String> cookies = setCookieHeaders(response);
        Optional<String> access = findCookie(cookies, "access_token");
        Optional<String> refresh = findCookie(cookies, "refresh_token");
        assertThat(access).isPresent();
        assertThat(refresh).isPresent();
        assertThat(access.get()).contains("Max-Age=3600").contains("HttpOnly").contains("Path=/");
        assertThat(refresh.get()).contains("Max-Age=1209600").contains("HttpOnly").contains("Path=/");

        // assert: DB insert
        assertThat(userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID)).isPresent();
    }

    @DisplayName("POST /api/v1/auth/kakao/callback - 기존 사용자면 프로필을 갱신하고 중복 row를 생성하지 않는다 (AC-3).")
    @Test
    void kakaoCallback_existingUser_updatesProfileNotDuplicate() {
        // arrange: 1차 로그인
        stubKakaoSuccess("처음닉", "first.png");
        callKakaoCallback("code-1");

        // act: 2차 로그인 (같은 kakao_user_id, 다른 프로필)
        wireMock.resetAll();
        stubKakaoSuccess("새닉", "new.png");
        ResponseEntity<JsonNode> response = callKakaoCallback("code-2");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UserModel> all = userJpaRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getNickname()).isEqualTo("새닉");
        assertThat(all.get(0).getProfileImageUrl()).isEqualTo("new.png");
    }

    @DisplayName("GET /api/v1/auth/kakao/login-url - 응답 본문에는 loginUrl 키만 있고 client_id 자체 키는 노출되지 않는다 (AC-6).")
    @Test
    void loginUrl_doesNotExposeClientId() {
        // act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/v1/auth/kakao/login-url",
                HttpMethod.GET,
                new HttpEntity<>(null),
                JsonNode.class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.has("loginUrl")).isTrue();
        // 응답 JSON 키 자체로 client_id가 노출되지 않아야 함 (loginUrl 내부 쿼리스트링은 OAuth URL 일부이므로 정상)
        assertThat(data.has("client_id")).isFalse();
        assertThat(data.has("clientId")).isFalse();
    }

    @DisplayName("POST /api/v1/auth/token/refresh - 유효한 refresh token이면 신규 쿠키 2건을 반환하고 DB 해시를 교체한다 (AC-7).")
    @Test
    void refresh_validToken_rotatesAndReplacesHash() {
        // arrange: 로그인하여 refresh 쿠키 획득
        stubKakaoSuccess("닉", "p.png");
        ResponseEntity<JsonNode> loginRes = callKakaoCallback("code-login");
        String refreshCookie = findCookie(setCookieHeaders(loginRes), "refresh_token").orElseThrow();
        String refreshValue = extractCookieValue(refreshCookie, "refresh_token");

        String oldHash = userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID).orElseThrow().getRefreshTokenHash();
        assertThat(oldHash).isNotBlank();

        // act: refresh 호출
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshValue);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        // assert: 신규 쿠키 2건
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = setCookieHeaders(response);
        assertThat(findCookie(cookies, "access_token")).isPresent();
        assertThat(findCookie(cookies, "refresh_token")).isPresent();

        // assert: DB 해시 교체
        String newHash = userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID).orElseThrow().getRefreshTokenHash();
        assertThat(newHash).isNotBlank().isNotEqualTo(oldHash);
    }

    @DisplayName("POST /api/v1/auth/token/refresh - 유효하지 않은 토큰이면 401 AUTH_REFRESH_TOKEN_INVALID를 반환한다 (AC-8).")
    @Test
    void refresh_invalidToken_returns401() {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=garbage-token");

        // act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/v1/auth/token/refresh",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @DisplayName("POST /api/v1/auth/logout - 만료 쿠키 2건을 반환하고 DB 해시를 null로 비운다 (AC-9).")
    @Test
    void logout_clearsCookiesAndDbHash() {
        // arrange: 로그인
        stubKakaoSuccess("닉", "p.png");
        ResponseEntity<JsonNode> loginRes = callKakaoCallback("code-login");
        String accessCookie = findCookie(setCookieHeaders(loginRes), "access_token").orElseThrow();
        String accessValue = extractCookieValue(accessCookie, "access_token");

        // act
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=" + accessValue);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        // assert: 200 + 만료 쿠키 2건
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = setCookieHeaders(response);
        String access = findCookie(cookies, "access_token").orElseThrow();
        String refresh = findCookie(cookies, "refresh_token").orElseThrow();
        assertThat(access).contains("Max-Age=0");
        assertThat(refresh).contains("Max-Age=0");

        // assert: DB 해시 null
        UserModel user = userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID).orElseThrow();
        assertThat(user.getRefreshTokenHash()).isNull();
    }

    @DisplayName("POST /api/v1/auth/kakao/callback - 카카오 5xx 응답이면 502 AUTH_KAKAO_API_FAILED를 반환한다 (AC-10).")
    @Test
    void kakaoCallback_kakao5xx_returns502() {
        // arrange: 카카오 토큰 엔드포인트 5xx
        wireMock.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withStatus(500)));

        // act
        ResponseEntity<JsonNode> response = callKakaoCallback("test-code");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_KAKAO_API_FAILED");
    }

    @DisplayName("POST /api/v1/auth/kakao/callback - code가 공백이면 400을 반환한다 (AC-14).")
    @Test
    void kakaoCallback_blankCode_returns400() {
        // act
        ResponseEntity<JsonNode> response = callKakaoCallback("");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DisplayName("POST /api/v1/auth/kakao/callback - 탈퇴한 사용자가 동일 kakao_user_id로 재로그인하면 신규 빈 계정을 생성하고 200을 반환한다 (FR-24 재가입).")
    @Test
    void kakaoCallback_deactivatedUser_rejoinsAsNewAccount() {
        // arrange: 사전에 탈퇴(soft-delete) 사용자 저장 — deleted_at 행이 유지된다.
        UserModel deactivated = UserModel.create(KAKAO_USER_ID, "닉", "p.png");
        deactivated.delete();
        userJpaRepository.save(deactivated);

        stubKakaoSuccess("새닉", "new.png");

        // act
        ResponseEntity<JsonNode> response = callKakaoCallback("test-code");

        // assert: FR-24 — 활성 조회 미스 → 신규 계정 생성, 정상 로그인(200) + 쿠키 발급
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = setCookieHeaders(response);
        assertThat(findCookie(cookies, "access_token")).isPresent();
        assertThat(findCookie(cookies, "refresh_token")).isPresent();

        // assert: 기존 탈퇴 행은 유지되고, 새 활성 계정이 별도로 생성됨(빈 계정 — 이전 데이터 미연결).
        assertThat(userJpaRepository.findAll()).hasSize(2);
        UserModel active = userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID).orElseThrow();
        assertThat(active.getId()).isNotEqualTo(deactivated.getId());
        assertThat(active.isActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // 보안/CORS 보강 테스트 (AC-5, AC-15, AC-16)
    // ------------------------------------------------------------------

    private SecretKey jwtSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<JsonNode> callProtectedEndpoint(String accessTokenCookieValue) {
        HttpHeaders headers = new HttpHeaders();
        if (accessTokenCookieValue != null) {
            headers.add(HttpHeaders.COOKIE, "access_token=" + accessTokenCookieValue);
        }
        return restTemplate.exchange(
                "/api/v1/protected/test",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );
    }

    @DisplayName("보호 엔드포인트 - 쿠키 없음/변조/만료/typ 불일치 access_token이면 401을 반환한다 (AC-5).")
    @Test
    void protectedEndpoint_invalidAccessToken_returns401() {
        // case 1: 쿠키 없음 → 401 (JwtAuthenticationEntryPoint)
        ResponseEntity<JsonNode> noCookie = callProtectedEndpoint(null);
        assertThat(noCookie.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // case 2: 변조된 access_token → 401
        String validAccess = jwtTokenProvider.issueAccessToken(1L);
        String tampered = validAccess.substring(0, validAccess.length() - 4) + "AAAA";
        ResponseEntity<JsonNode> tamperedRes = callProtectedEndpoint(tampered);
        assertThat(tamperedRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // case 3: 만료된 access_token → 401
        String expired = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("1")
                .claim("typ", "access")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(jwtSigningKey())
                .compact();
        ResponseEntity<JsonNode> expiredRes = callProtectedEndpoint(expired);
        assertThat(expiredRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // case 4: typ=refresh 토큰을 access_token 쿠키에 → 401 (Q2 typ claim 검증)
        String refreshAsAccess = jwtTokenProvider.issueRefreshToken(1L);
        ResponseEntity<JsonNode> wrongTypRes = callProtectedEndpoint(refreshAsAccess);
        assertThat(wrongTypRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @DisplayName("CORS preflight - 허용된 origin이면 Access-Control-Allow-Credentials=true와 함께 2xx를 반환한다 (AC-15).")
    @Test
    void corsPreflight_allowedOrigin_returns2xxWithCredentials() {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:3000");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");

        // act
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/kakao/callback",
                HttpMethod.OPTIONS,
                new HttpEntity<>(headers),
                String.class
        );

        // assert: 2xx (보통 200 OK)
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:3000");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
    }

    @DisplayName("CORS preflight - 허용되지 않은 origin이면 CORS가 거부한다 (AC-16).")
    @Test
    void corsPreflight_disallowedOrigin_isRejected() {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://evil.example.com");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");

        // act
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/kakao/callback",
                HttpMethod.OPTIONS,
                new HttpEntity<>(headers),
                String.class
        );

        // assert: Spring CORS 거부 - 403 OR Allow-Origin 헤더 부재
        boolean rejected = response.getStatusCode() == HttpStatus.FORBIDDEN
                || response.getHeaders().getFirst("Access-Control-Allow-Origin") == null;
        assertThat(rejected).isTrue();
    }

    // ------------------------------------------------------------------
    // P1: Kakao 네이티브 로그인 (AC-6, AC-7, AC-8)
    // ------------------------------------------------------------------

    private void stubKakaoUserInfo(long kakaoUserId, String nickname, String profileImageUrl) {
        // 네이티브 로그인 앱 귀속 검증: access_token_info 가 우리 앱(app_id=123456, test yml) 토큰임을 반환.
        stubKakaoAccessTokenInfo(kakaoUserId, KAKAO_APP_ID);

        String userJson = """
                {
                  "id": %d,
                  "properties": {
                    "nickname": "%s",
                    "profile_image": "%s"
                  }
                }
                """.formatted(kakaoUserId, nickname, profileImageUrl);
        wireMock.stubFor(get(urlEqualTo("/v2/user/me"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(userJson)));
    }

    private void stubKakaoAccessTokenInfo(long kakaoUserId, long appId) {
        String body = """
                {
                  "id": %d,
                  "expires_in": 3600,
                  "app_id": %d
                }
                """.formatted(kakaoUserId, appId);
        wireMock.stubFor(get(urlEqualTo("/v1/user/access_token_info"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @DisplayName("AC-6: POST /api/v1/auth/kakao/native - 유효 토큰이면 본문 토큰 3종을 반환하고 Set-Cookie 가 없다.")
    @Test
    void kakaoNative_validToken_returnsBodyTokensWithoutCookies() {
        stubKakaoUserInfo(KAKAO_USER_ID, "네이티브닉", "http://img.example/p.png");

        ResponseEntity<JsonNode> response = callKakaoNative("kakao-access");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookieHeaders(response)).isEmpty();
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("accessToken").asText()).isNotBlank();
        assertThat(data.get("refreshToken").asText()).isNotBlank();
        assertThat(data.get("expiresIn").asLong()).isEqualTo(jwtProperties.accessTtlSeconds());
        assertThat(userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID)).isPresent();
    }

    @DisplayName("AC-7: POST /api/v1/auth/kakao/native - 동일 토큰 2회 호출해도 계정 중복 없이 각각 새 JWT 를 발급한다.")
    @Test
    void kakaoNative_twiceSequential_noDuplicateAccount() {
        stubKakaoUserInfo(KAKAO_USER_ID, "네이티브닉", "p.png");

        ResponseEntity<JsonNode> first = callKakaoNative("kakao-access");
        ResponseEntity<JsonNode> second = callKakaoNative("kakao-access");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userJpaRepository.findAll()).hasSize(1);
        // 각각 토큰 발급됨 (refresh rotation 으로 마지막 호출 토큰만 유효).
        assertThat(first.getBody().get("data").get("accessToken").asText()).isNotBlank();
        assertThat(second.getBody().get("data").get("accessToken").asText()).isNotBlank();
    }

    @DisplayName("AC-8: POST /api/v1/auth/kakao/native - 위변조 토큰(카카오 4xx)이면 502 AUTH_KAKAO_API_FAILED.")
    @Test
    void kakaoNative_kakao4xx_returns502() {
        // 앱 귀속 검증은 통과(우리 앱 토큰)시키고, /v2/user/me 단계에서 4xx 를 시뮬레이션.
        stubKakaoAccessTokenInfo(KAKAO_USER_ID, KAKAO_APP_ID);
        wireMock.stubFor(get(urlEqualTo("/v2/user/me"))
                .willReturn(aResponse().withStatus(401)));

        ResponseEntity<JsonNode> response = callKakaoNative("forged-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_KAKAO_API_FAILED");
    }

    @DisplayName("AC-8: POST /api/v1/auth/kakao/native - 위변조 토큰(access_token_info 자체 4xx)이면 502 AUTH_KAKAO_API_FAILED.")
    @Test
    void kakaoNative_accessTokenInfo4xx_returns502() {
        // 앱 귀속 검증 단계(access_token_info)에서 바로 4xx(401) → 위변조 토큰 경로. /v2/user/me 미도달.
        wireMock.stubFor(get(urlEqualTo("/v1/user/access_token_info"))
                .willReturn(aResponse().withStatus(401)));

        ResponseEntity<JsonNode> response = callKakaoNative("forged-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_KAKAO_API_FAILED");
        assertThat(userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID)).isEmpty();
    }

    @DisplayName("앱 귀속 검증: POST /api/v1/auth/kakao/native - 다른 앱(app_id 불일치) 토큰이면 401 AUTH_KAKAO_APP_MISMATCH.")
    @Test
    void kakaoNative_foreignAppToken_returns401() {
        // access_token_info 가 우리 앱과 다른 app_id 를 반환 → 거부. /v2/user/me 까지 도달하지 않는다.
        stubKakaoAccessTokenInfo(KAKAO_USER_ID, KAKAO_APP_ID + 1);

        ResponseEntity<JsonNode> response = callKakaoNative("other-app-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_KAKAO_APP_MISMATCH");
        assertThat(userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID)).isEmpty();
    }

    @DisplayName("AC-15: POST /api/v1/auth/kakao/native - 탈퇴한 Kakao 사용자가 동일 oauthId로 재로그인하면 신규 빈 계정을 생성하고 200을 반환한다 (FR-24 재가입).")
    @Test
    void kakaoNative_deactivatedUser_rejoinsAsNewAccount() {
        // 사전에 탈퇴(soft-delete) Kakao 계정 저장 — soft-delete 행은 유지된다.
        UserModel deactivated = UserModel.create(KAKAO_USER_ID, "닉", "p.png");
        deactivated.delete();
        userJpaRepository.save(deactivated);

        // 우리 앱 토큰 + 같은 kakaoUserId 반환 stub.
        stubKakaoUserInfo(KAKAO_USER_ID, "새닉", "new.png");

        ResponseEntity<JsonNode> response = callKakaoNative("kakao-access");

        // FR-24: 활성 조회 미스 → 신규 계정 생성, 정상 로그인(200) + 본문 토큰 발급(Set-Cookie 없음).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookieHeaders(response)).isEmpty();
        assertThat(response.getBody().get("data").get("accessToken").asText()).isNotBlank();

        // 기존 탈퇴 행은 유지되고, 새 활성 계정이 별도로 생성됨(빈 계정 — 이전 데이터 미연결).
        assertThat(userJpaRepository.findAll()).hasSize(2);
        UserModel active = userJpaRepository.findByKakaoUserIdAndDeletedAtIsNull(KAKAO_USER_ID).orElseThrow();
        assertThat(active.getId()).isNotEqualTo(deactivated.getId());
        assertThat(active.isActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // P1: Apple 네이티브 로그인 (AC-9 ~ AC-15)
    // ------------------------------------------------------------------

    private String appleBody(String identityToken, String nonce, String givenName, String familyName, String email) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"identityToken\":\"").append(identityToken).append("\"");
        sb.append(",\"nonce\":\"").append(nonce).append("\"");
        if (givenName != null || familyName != null) {
            sb.append(",\"fullName\":{");
            sb.append("\"givenName\":").append(givenName == null ? "null" : "\"" + givenName + "\"");
            sb.append(",\"familyName\":").append(familyName == null ? "null" : "\"" + familyName + "\"");
            sb.append("}");
        }
        if (email != null) {
            sb.append(",\"email\":\"").append(email).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    @DisplayName("AC-9/AC-10: POST /api/v1/auth/apple/native - 유효 토큰+nonce 면 본문 토큰 반환(Set-Cookie 없음)하고 최초 email/fullName 을 저장한다.")
    @Test
    void appleNative_validToken_returnsTokensAndStoresProfile() {
        String nonce = "client-nonce-1";
        String token = issueAppleToken("apple-sub-1", nonce, APPLE_AUDIENCE,
                System.currentTimeMillis() + 600_000, "relay@privaterelay.appleid.com");

        ResponseEntity<JsonNode> response = callAppleNative(
                appleBody(token, nonce, "길동", "홍", "relay@privaterelay.appleid.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookieHeaders(response)).isEmpty();
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("accessToken").asText()).isNotBlank();
        assertThat(data.get("expiresIn").asLong()).isEqualTo(jwtProperties.accessTtlSeconds());

        UserModel stored = userJpaRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider.APPLE, "apple-sub-1")
                .orElseThrow();
        assertThat(stored.getNickname()).isEqualTo("길동 홍"); // AC-10: fullName 저장 (BR-12)
        assertThat(stored.getEmail()).isEqualTo("relay@privaterelay.appleid.com");
        assertThat(stored.getKakaoUserId()).isNull();
    }

    @DisplayName("AC-11: POST /api/v1/auth/apple/native - 재로그인 시 email/fullName null 전송해도 기존 값이 유지된다 (BR-9).")
    @Test
    void appleNative_reLogin_nullProfile_keepsExistingValues() {
        String nonce = "client-nonce-1";
        // 최초 로그인: fullName/email 저장.
        String firstToken = issueAppleToken("apple-sub-2", nonce, APPLE_AUDIENCE,
                System.currentTimeMillis() + 600_000, "first@privaterelay.appleid.com");
        callAppleNative(appleBody(firstToken, nonce, "철수", "김", "first@privaterelay.appleid.com"));

        // 재로그인: fullName/email 미전송, 토큰 클레임에도 email 없음.
        String secondToken = issueAppleToken("apple-sub-2", nonce, APPLE_AUDIENCE,
                System.currentTimeMillis() + 600_000, null);
        ResponseEntity<JsonNode> response = callAppleNative(appleBody(secondToken, nonce, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userJpaRepository.findAll()).hasSize(1);
        UserModel stored = userJpaRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider.APPLE, "apple-sub-2")
                .orElseThrow();
        assertThat(stored.getNickname()).isEqualTo("철수 김"); // 불변
        assertThat(stored.getEmail()).isEqualTo("first@privaterelay.appleid.com"); // 불변
    }

    @DisplayName("AC-12: POST /api/v1/auth/apple/native - aud 불일치면 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void appleNative_wrongAudience_returns401() {
        String nonce = "client-nonce-1";
        String token = issueAppleToken("apple-sub-3", nonce, "com.evil.app",
                System.currentTimeMillis() + 600_000, null);

        ResponseEntity<JsonNode> response = callAppleNative(appleBody(token, nonce, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_APPLE_TOKEN_INVALID");
    }

    @DisplayName("AC-13: POST /api/v1/auth/apple/native - 만료 토큰이면 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void appleNative_expiredToken_returns401() {
        String nonce = "client-nonce-1";
        String token = issueAppleToken("apple-sub-4", nonce, APPLE_AUDIENCE,
                System.currentTimeMillis() - 60_000, null);

        ResponseEntity<JsonNode> response = callAppleNative(appleBody(token, nonce, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_APPLE_TOKEN_INVALID");
    }

    @DisplayName("AC-14: POST /api/v1/auth/apple/native - nonce 불일치면 401 AUTH_APPLE_TOKEN_INVALID.")
    @Test
    void appleNative_nonceMismatch_returns401() {
        String token = issueAppleToken("apple-sub-5", "token-nonce", APPLE_AUDIENCE,
                System.currentTimeMillis() + 600_000, null);

        ResponseEntity<JsonNode> response = callAppleNative(appleBody(token, "different-nonce", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_APPLE_TOKEN_INVALID");
    }

    @DisplayName("AC-15: POST /api/v1/auth/apple/native - 탈퇴한 Apple 사용자가 동일 oauthId로 재로그인하면 신규 빈 계정을 생성하고 200을 반환한다 (FR-24 재가입).")
    @Test
    void appleNative_deactivatedUser_rejoinsAsNewAccount() {
        // 사전에 탈퇴(soft-delete) Apple 계정 저장 — soft-delete 행은 유지된다.
        UserModel deactivated = UserModel.createOauth(OauthProvider.APPLE, "apple-sub-6", "Apple 사용자", null, null);
        deactivated.delete();
        userJpaRepository.save(deactivated);

        String nonce = "client-nonce-1";
        String token = issueAppleToken("apple-sub-6", nonce, APPLE_AUDIENCE,
                System.currentTimeMillis() + 600_000, null);

        ResponseEntity<JsonNode> response = callAppleNative(appleBody(token, nonce, null, null, null));

        // FR-24: 활성 조회 미스 → 신규 계정 생성, 정상 로그인(200) + 본문 토큰 발급.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookieHeaders(response)).isEmpty();
        assertThat(response.getBody().get("data").get("accessToken").asText()).isNotBlank();

        // 기존 탈퇴 행은 유지되고, 새 활성 계정이 별도로 생성됨(빈 계정 — 이전 데이터 미연결).
        assertThat(userJpaRepository.findAll()).hasSize(2);
        UserModel active = userJpaRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider.APPLE, "apple-sub-6")
                .orElseThrow();
        assertThat(active.getId()).isNotEqualTo(deactivated.getId());
        assertThat(active.isActive()).isTrue();
    }

    @DisplayName("AC-22: POST /api/v1/auth/apple/native - fullName 없으면 임시 닉네임('Apple 사용자')으로 계정을 생성한다.")
    @Test
    void appleNative_noFullName_createsWithTemporaryNickname() {
        String nonce = "client-nonce-1";
        String token = issueAppleToken("apple-sub-7", nonce, APPLE_AUDIENCE,
                System.currentTimeMillis() + 600_000, null);

        ResponseEntity<JsonNode> response = callAppleNative(appleBody(token, nonce, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserModel stored = userJpaRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider.APPLE, "apple-sub-7")
                .orElseThrow();
        assertThat(stored.getNickname()).isEqualTo("Apple 사용자");
    }

    // ------------------------------------------------------------------
    // P1: refresh (body) (AC-16, AC-17, AC-18)
    // ------------------------------------------------------------------

    private ResponseEntity<JsonNode> callRefreshBody(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                "{\"refreshToken\":\"" + refreshToken + "\"}", headers);
        return restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST, entity, JsonNode.class);
    }

    @DisplayName("AC-16: POST /api/v1/auth/refresh - 유효 refresh 면 새 쌍을 본문으로 반환하고 Set-Cookie 가 없다.")
    @Test
    void refreshBody_validToken_returnsNewPairWithoutCookies() {
        stubKakaoUserInfo(KAKAO_USER_ID, "닉", "p.png");
        String oldRefresh = callKakaoNative("kakao-access").getBody().get("data").get("refreshToken").asText();

        ResponseEntity<JsonNode> response = callRefreshBody(oldRefresh);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookieHeaders(response)).isEmpty();
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("accessToken").asText()).isNotBlank();
        assertThat(data.get("refreshToken").asText()).isNotBlank().isNotEqualTo(oldRefresh);
        assertThat(data.get("expiresIn").asLong()).isEqualTo(jwtProperties.accessTtlSeconds());
    }

    @DisplayName("AC-17: POST /api/v1/auth/refresh - 사용된(rotate 된) refresh 재호출이면 401 AUTH_REFRESH_TOKEN_INVALID.")
    @Test
    void refreshBody_reusedToken_returns401() {
        stubKakaoUserInfo(KAKAO_USER_ID, "닉", "p.png");
        String oldRefresh = callKakaoNative("kakao-access").getBody().get("data").get("refreshToken").asText();

        // 1차 refresh 성공 → oldRefresh 무효화.
        callRefreshBody(oldRefresh);

        // 사용된 토큰 재호출.
        ResponseEntity<JsonNode> response = callRefreshBody(oldRefresh);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @DisplayName("AC-18: 기존 쿠키 /api/v1/auth/token/refresh 는 body refresh 추가 후에도 동일하게 동작한다 (회귀).")
    @Test
    void cookieRefresh_stillWorks_afterBodyRefreshAdded() {
        stubKakaoSuccess("닉", "p.png");
        ResponseEntity<JsonNode> loginRes = callKakaoCallback("code-login");
        String refreshCookie = findCookie(setCookieHeaders(loginRes), "refresh_token").orElseThrow();
        String refreshValue = extractCookieValue(refreshCookie, "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshValue);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/v1/auth/token/refresh", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(findCookie(setCookieHeaders(response), "access_token")).isPresent();
        assertThat(findCookie(setCookieHeaders(response), "refresh_token")).isPresent();
    }

}
