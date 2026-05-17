package com.wherewego.config.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActuatorIpRestrictionFilter} 단위 테스트 (Phase 2.6 PR-B B-2).
 *
 * <p>{@code /actuator/refresh} 경로에서 로컬호스트(127.0.0.1/::1/0:0:0:0:0:0:0:1) 만 통과,
 * 그 외 IP 는 403 + FORBIDDEN_ACTUATOR_ACCESS 응답을 검증한다.</p>
 */
class ActuatorIpRestrictionFilterTest {

    private static final String PROTECTED_PATH = "/actuator/refresh";

    private ActuatorIpRestrictionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActuatorIpRestrictionFilter();
    }

    private MockHttpServletRequest refreshRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PROTECTED_PATH);
        request.setServletPath(PROTECTED_PATH);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @DisplayName("로컬호스트 IP 일 때,")
    @Nested
    class LocalhostAccess {

        @DisplayName("127.0.0.1 이면 필터 체인을 통과한다.")
        @Test
        void localhostIpv4_passesChain() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = refreshRequest("127.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isSameAs(request);
        }

        @DisplayName("::1 이면 필터 체인을 통과한다.")
        @Test
        void localhostIpv6Short_passesChain() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = refreshRequest("::1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isSameAs(request);
        }

        @DisplayName("0:0:0:0:0:0:0:1 이면 필터 체인을 통과한다.")
        @Test
        void localhostIpv6Long_passesChain() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = refreshRequest("0:0:0:0:0:0:0:1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isSameAs(request);
        }
    }

    @DisplayName("비로컬호스트 IP 일 때,")
    @Nested
    class NonLocalhostAccess {

        @DisplayName("10.0.0.1 이면 403 FORBIDDEN_ACTUATOR_ACCESS 를 반환하고 체인은 호출되지 않는다.")
        @Test
        void privateNetwork_returns403() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = refreshRequest("10.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("FORBIDDEN_ACTUATOR_ACCESS");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @DisplayName("필터 적용 여부를 판단할 때,")
    @Nested
    class ShouldNotFilter {

        @DisplayName("/actuator/refresh 외 경로면 shouldNotFilter=true 를 반환한다.")
        @Test
        void shouldNotFilter_nonProtectedPath_returnsTrue() {
            // arrange
            MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
            health.setServletPath("/actuator/health");
            MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/v1/auth/me");
            api.setServletPath("/api/v1/auth/me");

            // act & assert
            Boolean nonProtected = (Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter",
                    (HttpServletRequest) health);
            Boolean apiPath = (Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter",
                    (HttpServletRequest) api);
            assertThat(nonProtected).isTrue();
            assertThat(apiPath).isTrue();
        }

        @DisplayName("/actuator/refresh 면 shouldNotFilter=false 를 반환한다.")
        @Test
        void shouldNotFilter_protectedPath_returnsFalse() {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("POST", PROTECTED_PATH);
            request.setServletPath(PROTECTED_PATH);

            // act & assert
            Boolean result = (Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter",
                    (HttpServletRequest) request);
            assertThat(result).isFalse();
        }
    }
}
