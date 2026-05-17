package com.wherewego.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatbotRateLimitFilter} 통합 테스트 (Phase 2.6 PR-B B-3).
 *
 * <p>capacity=3 으로 override 한 상태에서 동일 botUserKey 로 4회 연속 호출 시
 * 4번째 응답이 AC-B5 안내 메시지가 되는지, botUserKey 누락 시 AC-B9 안내가 응답되는지 검증한다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class ChatbotRateLimitFilterIT {

    private static final String SKILL_SECRET = "test-kakao-skill-secret";
    private static final String SKILL_HEADER = "X-Kakao-Skill-Secret";
    private static final String BOT_USER_KEY = "rate-limit-it-user-001";
    private static final String WEBHOOK_PATH = "/api/v1/chatbot/webhook";
    private static final String RATE_LIMITED_MESSAGE = "잠시 후 다시 시도해 주세요.";
    private static final String MISSING_KEY_MESSAGE =
            "일시적으로 이용에 불편이 있어요. 잠시 후 다시 시도해 주세요.";

    @DynamicPropertySource
    static void overrideRateLimit(DynamicPropertyRegistry registry) {
        // capacity=3 으로 축소 → 4회째 호출에서 차단되는 것을 짧은 시간에 확인.
        registry.add("chatbot.rate-limit.capacity", () -> "3");
        registry.add("chatbot.rate-limit.refill-seconds", () -> "60");
        // 외부 의존 차단 (회귀 방지)
        registry.add("place.instagram.scraping-enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ChatbotRateLimiter rateLimiter;

    @BeforeEach
    void resetRateLimiter() {
        rateLimiter.invalidateAll();
    }

    private ResponseEntity<JsonNode> webhookCall(String botUserKey) {
        String body = """
                {
                  "userRequest": {
                    "utterance": "ping",
                    "user": %s
                  },
                  "action": { "params": {} }
                }
                """.formatted(botUserKey == null
                ? "{ }"
                : "{ \"id\": \"" + botUserKey + "\", \"type\": \"botUserKey\" }");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SKILL_HEADER, SKILL_SECRET);

        return restTemplate.exchange(
                WEBHOOK_PATH,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
    }

    private static String simpleText(JsonNode body) {
        JsonNode outputs = body.path("template").path("outputs");
        if (!outputs.isArray() || outputs.isEmpty()) {
            return "";
        }
        return outputs.get(0).path("simpleText").path("text").asText("");
    }

    @DisplayName("POST /api/v1/chatbot/webhook - capacity=3 상태에서 동일 botUserKey 4번째 호출은 AC-B5 안내를 반환한다.")
    @Test
    void webhook_exceedingCapacity_returnsRateLimitGuide() {
        // act : 3회까지는 통과(파이프라인 처리), 4회째는 필터에서 차단되어 안내 메시지
        ResponseEntity<JsonNode> first = webhookCall(BOT_USER_KEY);
        ResponseEntity<JsonNode> second = webhookCall(BOT_USER_KEY);
        ResponseEntity<JsonNode> third = webhookCall(BOT_USER_KEY);
        ResponseEntity<JsonNode> fourth = webhookCall(BOT_USER_KEY);

        // assert : 모두 HTTP 200, 4번째 응답만 AC-B5 안내
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 1~3번째 응답은 rate-limit guide / missing-key guide 가 아닌 정상 webhook 응답이어야 한다.
        // (rate-limit 필터를 통과하여 후속 chatbot 파이프라인이 정상 응답을 작성).
        assertThat(simpleText(first.getBody()))
                .isNotEqualTo(RATE_LIMITED_MESSAGE)
                .isNotEqualTo(MISSING_KEY_MESSAGE);
        assertThat(simpleText(second.getBody()))
                .isNotEqualTo(RATE_LIMITED_MESSAGE)
                .isNotEqualTo(MISSING_KEY_MESSAGE);
        assertThat(simpleText(third.getBody()))
                .isNotEqualTo(RATE_LIMITED_MESSAGE)
                .isNotEqualTo(MISSING_KEY_MESSAGE);

        // 4번째 응답만 AC-B5 안내
        assertThat(simpleText(fourth.getBody())).isEqualTo(RATE_LIMITED_MESSAGE);
    }

    @DisplayName("POST /api/v1/chatbot/webhook - botUserKey 가 누락된 페이로드는 AC-B9 안내를 반환한다.")
    @Test
    void webhook_missingBotUserKey_returnsMissingGuide() {
        // act
        ResponseEntity<JsonNode> response = webhookCall(null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simpleText(response.getBody())).isEqualTo(MISSING_KEY_MESSAGE);
    }
}
