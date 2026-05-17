package com.wherewego.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatbotRateLimitFilter} 단위 테스트 (Phase 2.6 PR-B B-3).
 *
 * <p>경로 매칭, botUserKey 누락 안내(AC-B9), tryConsume=false 안내(AC-B5),
 * 정상 통과 시 BufferedRequestWrapper 전달을 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatbotRateLimitFilterTest {

    private static final String WEBHOOK_PATH = "/api/v1/chatbot/webhook";
    private static final String BOT_USER_KEY = "kakao-bot-user-001";
    private static final String MISSING_KEY_MESSAGE =
            "일시적으로 이용에 불편이 있어요. 잠시 후 다시 시도해 주세요.";
    private static final String RATE_LIMITED_MESSAGE = "잠시 후 다시 시도해 주세요.";

    @Mock
    private ChatbotRateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatbotRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ChatbotRateLimitFilter(rateLimiter, objectMapper);
    }

    private MockHttpServletRequest webhookRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", WEBHOOK_PATH);
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private String simpleText(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        return root.path("template").path("outputs").get(0).path("simpleText").path("text").asText();
    }

    @DisplayName("필터 적용 여부를 판단할 때,")
    @Nested
    class ShouldNotFilter {

        @DisplayName("/api/v1/chatbot/webhook + POST 가 아니면 shouldNotFilter=true 를 반환한다.")
        @Test
        void shouldNotFilter_nonWebhookPath_returnsTrue() {
            // arrange
            MockHttpServletRequest get = new MockHttpServletRequest("POST", "/api/v1/auth/kakao/callback");
            MockHttpServletRequest wrongMethod = new MockHttpServletRequest("GET", WEBHOOK_PATH);

            // act & assert (protected method 는 ReflectionTestUtils 로 호출)
            Boolean nonPath = (Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter",
                    (HttpServletRequest) get);
            Boolean nonMethod = (Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter",
                    (HttpServletRequest) wrongMethod);
            assertThat(nonPath).isTrue();
            assertThat(nonMethod).isTrue();
        }

        @DisplayName("/api/v1/chatbot/webhook + POST 이면 shouldNotFilter=false 를 반환한다.")
        @Test
        void shouldNotFilter_webhookPost_returnsFalse() {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("POST", WEBHOOK_PATH);

            // act & assert
            Boolean result = (Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter",
                    (HttpServletRequest) request);
            assertThat(result).isFalse();
        }
    }

    @DisplayName("doFilterInternal 을 호출할 때,")
    @Nested
    class DoFilterInternal {

        @DisplayName("botUserKey 가 누락되면 HTTP 200 + AC-B9 안내 메시지를 응답하고 체인을 호출하지 않는다.")
        @Test
        void doFilterInternal_missingBotUserKey_returnsGuide() throws ServletException, IOException {
            // arrange : userRequest.user.id 누락된 본문
            String body = """
                    {
                      "userRequest": { "user": { } },
                      "action": { "params": {} }
                    }
                    """;
            MockHttpServletRequest request = webhookRequest(body);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(simpleText(response.getContentAsString())).isEqualTo(MISSING_KEY_MESSAGE);
            assertThat(chain.getRequest()).isNull();
            verify(rateLimiter, never()).tryConsume(eq(BOT_USER_KEY));
        }

        @DisplayName("botUserKey 가 정상이고 tryConsume=false 면 HTTP 200 + AC-B5 안내 메시지를 응답한다.")
        @Test
        void doFilterInternal_rateLimited_returnsGuide() throws ServletException, IOException {
            // arrange
            String body = """
                    {
                      "userRequest": { "user": { "id": "%s", "type": "botUserKey" } },
                      "action": { "params": {} }
                    }
                    """.formatted(BOT_USER_KEY);
            MockHttpServletRequest request = webhookRequest(body);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            when(rateLimiter.tryConsume(BOT_USER_KEY)).thenReturn(false);

            // act
            filter.doFilter(request, response, chain);

            // assert
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(simpleText(response.getContentAsString())).isEqualTo(RATE_LIMITED_MESSAGE);
            assertThat(chain.getRequest()).isNull();
        }

        @DisplayName("botUserKey 가 정상이고 tryConsume=true 면 BufferedRequestWrapper 로 체인을 호출한다.")
        @Test
        void doFilterInternal_passes_invokesChainWithWrapper() throws ServletException, IOException {
            // arrange
            String body = """
                    {
                      "userRequest": { "user": { "id": "%s", "type": "botUserKey" } },
                      "action": { "params": {} }
                    }
                    """.formatted(BOT_USER_KEY);
            MockHttpServletRequest request = webhookRequest(body);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            when(rateLimiter.tryConsume(BOT_USER_KEY)).thenReturn(true);

            // act
            filter.doFilter(request, response, chain);

            // assert : 체인이 호출되었고 전달된 요청은 BufferedRequestWrapper 이다 (body 재읽기 가능).
            assertThat(chain.getRequest()).isInstanceOf(BufferedRequestWrapper.class);
            BufferedRequestWrapper wrapped = (BufferedRequestWrapper) chain.getRequest();
            assertThat(new String(wrapped.getCachedBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        }
    }
}
