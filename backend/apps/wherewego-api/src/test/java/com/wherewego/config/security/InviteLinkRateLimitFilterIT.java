package com.wherewego.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InviteLinkRateLimitFilter} 통합 테스트 (ZT 감사 HIGH-2).
 *
 * <p>IC-1 재사용 모델로 유효 토큰의 accept 반복 호출이 가능해져, accept(POST) 경로에도
 * by-slug(GET) 와 동일한 IP 기반 레이트리밋을 적용했다. capacity=3 으로 override 한 상태에서
 * 동일 IP 의 4회째 호출이 429 + INVITE_LINK_RATE_LIMITED 로 차단되는지,
 * by-slug 와 accept 가 IP 예산을 공유하는지 검증한다.</p>
 *
 * <p>레이트리밋 필터는 {@code JwtAuthenticationFilter} 보다 먼저 실행되므로(SecurityConfig
 * addFilterBefore), 유효 토큰 없이도 capacity 초과 호출이 인증 처리 전에 IP 로 차단된다.</p>
 *
 * <p>{@link InviteLinkRateLimiter} 는 싱글톤이며 클래스 내 카운터가 누적되므로, IP 예산 공유와
 * accept 초과 차단을 단일 테스트 시나리오로 검증해 키 누적 간섭을 피한다.</p>
 *
 * <p>또한 accept 경로에 matrix variable({@code ;x=1}) 을 붙여 레이트리밋 매칭을 우회하려는
 * 시도가 차단되는지 검증한다. matrix variable 경로는 Spring Security 인가 매칭(by-slug permitAll
 * 제외)에 실패해 accept 컨트롤러에 도달하지 못하고 401 로 선차단되므로, 반복 호출이 누적되어도
 * accept 비즈니스 로직이 우회 실행되지 않는다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class InviteLinkRateLimitFilterIT {

    private static final String ACCEPT_PATH = "/api/v1/groups/invite-links/dummy-token/accept";
    private static final String BY_SLUG_PATH = "/api/v1/groups/invite-links/by-slug/dummy-slug";
    // matrix variable 우회 시도 경로. getServletPath()+정규화로 세미콜론 이후가 잘려 accept 로 매칭되어야 한다.
    private static final String ACCEPT_PATH_WITH_MATRIX =
            "/api/v1/groups/invite-links/dummy-token/accept;x=1";

    @DynamicPropertySource
    static void overrideRateLimit(DynamicPropertyRegistry registry) {
        // capacity=3 으로 축소 → 4회째 호출에서 차단되는 것을 짧은 시간에 확인.
        registry.add("app.invite.rate-limit.capacity", () -> "3");
        registry.add("app.invite.rate-limit.refill-seconds", () -> "60");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private ResponseEntity<JsonNode> acceptCall() {
        return restTemplate.exchange(
                ACCEPT_PATH,
                HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> bySlugCall() {
        return restTemplate.exchange(
                BY_SLUG_PATH,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                JsonNode.class);
    }

    @DisplayName("by-slug(GET)+accept(POST) - capacity=3 동일 IP 예산 공유 상태에서 4번째 accept 가 429 INVITE_LINK_RATE_LIMITED 를 반환한다.")
    @Test
    void accept_exceedingSharedIpBudget_returns429() {
        // act : by-slug 1 + accept 2 = capacity(3) 소진. 4회째 accept 는 IP 예산 초과로 차단.
        ResponseEntity<JsonNode> first = bySlugCall();   // 1 - 통과(레이트리밋 미차단)
        ResponseEntity<JsonNode> second = acceptCall();  // 2 - 통과
        ResponseEntity<JsonNode> third = acceptCall();   // 3 - 통과
        ResponseEntity<JsonNode> fourth = acceptCall();  // 4 - 초과 → 429

        // assert : 1~3회는 레이트리밋에 걸리지 않는다(인증 미충족 등 다른 응답이며 429 가 아님).
        assertThat(first.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // 4번째 accept 만 레이트리밋(429 + INVITE_LINK_RATE_LIMITED)
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        JsonNode body = fourth.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("errorCode").asText()).isEqualTo("INVITE_LINK_RATE_LIMITED");
    }

    @DisplayName("accept 경로에 matrix variable(;x=1)을 붙여 레이트리밋을 우회하려 해도, capacity 를 넘는 반복 호출이 accept 컨트롤러로 처리되지 않는다(우회 차단).")
    @Test
    void acceptWithMatrixVariable_doesNotBypassRateLimit() {
        // arrange : 기존 시나리오와 IP 예산 간섭을 피하려 X-Forwarded-For 로 별도 IP 부여.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "203.0.113.9");

        // act : matrix variable 을 붙인 accept 를 capacity(3) 예산을 초과(5회)할 만큼 반복 호출한다.
        //       raw 세미콜론이 그대로 서버에 전달되도록 절대 URI 를 명시 구성한다.
        // assert : matrix variable 경로는 Spring Security 인가 매칭(by-slug permitAll 제외)에 실패해
        //          accept 컨트롤러에 도달하지 못하고 401 로 선차단된다. 따라서 반복 호출이 누적되어도
        //          accept 비즈니스 로직이 우회 실행되지 않는다(=레이트리밋 우회로 accept 가 성공하지 못함).
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<JsonNode> resp = matrixAcceptCall(headers);
            // 우회 성공의 신호(2xx accept 성공)가 결코 나타나지 않아야 한다.
            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("matrix variable accept 가 우회 실행되어 성공해서는 안 된다 (i=%d)", i)
                    .isFalse();
        }
    }

    private ResponseEntity<JsonNode> matrixAcceptCall(HttpHeaders headers) {
        URI uri = URI.create("http://localhost:" + port + ACCEPT_PATH_WITH_MATRIX);
        return restTemplate.exchange(
                uri,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                JsonNode.class);
    }
}
