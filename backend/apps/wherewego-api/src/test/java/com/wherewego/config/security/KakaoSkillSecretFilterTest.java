package com.wherewego.config.security;

import com.wherewego.config.env.KakaoApiProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoSkillSecretFilterTest {

    private static final String EXPECTED_SECRET = "expected-secret";

    private KakaoSkillSecretFilter filter;

    @BeforeEach
    void setUp() {
        KakaoApiProperties properties = new KakaoApiProperties(
                "local-api-key",
                new KakaoApiProperties.OAuth("cid", "csec", "https://example/redirect"),
                new KakaoApiProperties.Local("https://dapi.kakao.com", 2_000),
                new KakaoApiProperties.Skill(EXPECTED_SECRET),
                new KakaoApiProperties.Callback(3_000)
        );
        filter = new KakaoSkillSecretFilter(properties);
    }

    @DisplayName("Skill 비밀 헤더를 검사할 때,")
    @Nested
    class DoFilterInternal {

        @DisplayName("헤더가 일치하면, 필터 체인을 통과한다.")
        @Test
        void doFilterInternal_secretMatches_passesChain() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.addHeader(KakaoSkillSecretFilter.HEADER, EXPECTED_SECRET);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isSameAs(request);
        }

        @DisplayName("헤더가 일치하지 않으면, 401 을 반환한다.")
        @Test
        void doFilterInternal_secretMismatch_returns401() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.addHeader(KakaoSkillSecretFilter.HEADER, "wrong-secret");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("BOT_SKILL_SECRET_INVALID");
            assertThat(chain.getRequest()).isNull();
        }

        @DisplayName("헤더가 누락되면, 401 을 반환한다.")
        @Test
        void doFilterInternal_headerMissing_returns401() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("BOT_SKILL_SECRET_INVALID");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @DisplayName("필터 적용 여부를 판단할 때,")
    @Nested
    class ShouldNotFilter {

        @DisplayName("/api/v1/chatbot/ 외 경로면, shouldNotFilter=true 로 패스한다.")
        @Test
        void shouldNotFilter_nonChatbotPath_returnsTrue() throws ServletException, IOException {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/kakao/callback");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act - shouldNotFilter=true 이면 chain 만 호출되고 401 응답이 나오지 않는다
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isSameAs(request);
        }
    }
}
