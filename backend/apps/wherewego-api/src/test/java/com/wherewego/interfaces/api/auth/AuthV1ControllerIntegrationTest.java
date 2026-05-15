package com.wherewego.interfaces.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.config.env.JwtProperties;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
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

    @DynamicPropertySource
    static void overrideKakaoUrls(DynamicPropertyRegistry registry) {
        registry.add("kakao.oauth.token-base-url", wireMock::baseUrl);
        registry.add("kakao.oauth.user-base-url", wireMock::baseUrl);
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

    @BeforeEach
    void cleanUp() {
        userJpaRepository.deleteAll();
        wireMock.resetAll();
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
        assertThat(userJpaRepository.findByKakaoUserId(KAKAO_USER_ID)).isPresent();
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

        String oldHash = userJpaRepository.findByKakaoUserId(KAKAO_USER_ID).orElseThrow().getRefreshTokenHash();
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
        String newHash = userJpaRepository.findByKakaoUserId(KAKAO_USER_ID).orElseThrow().getRefreshTokenHash();
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
        UserModel user = userJpaRepository.findByKakaoUserId(KAKAO_USER_ID).orElseThrow();
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

    @DisplayName("POST /api/v1/auth/kakao/callback - 탈퇴한 사용자가 로그인 시도하면 401 AUTH_USER_DEACTIVATED를 반환한다 (AC-13).")
    @Test
    void kakaoCallback_deactivatedUser_returns401() {
        // arrange: 사전에 탈퇴 사용자 저장
        UserModel user = UserModel.create(KAKAO_USER_ID, "닉", "p.png");
        user.delete();
        userJpaRepository.save(user);

        stubKakaoSuccess("닉", "p.png");

        // act
        ResponseEntity<JsonNode> response = callKakaoCallback("test-code");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("AUTH_USER_DEACTIVATED");
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

}
