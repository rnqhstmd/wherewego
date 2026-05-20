package com.wherewego.interfaces.api.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.config.security.ChatbotRateLimiter;
import com.wherewego.domain.bot.BotUserMapping;
import com.wherewego.domain.chatbot.PendingInstagramSession;
import com.wherewego.domain.chatbot.PendingNotificationSession;
import com.wherewego.infrastructure.bot.BotUserMappingJpaRepository;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.domain.user.UserModel;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인스타 링크 → 메모 흐름(2026-05-20 v2 재설계)의 e2e 검증.
 *
 * <p>커버 범위:
 * <ul>
 *   <li>시나리오 A 진입: URL 받은 직후 안내문 + QuickReply 1개("❌ 메모 없이 저장") 응답</li>
 *   <li>prepend 훅: {@link PendingNotificationSession}에 적재된 알림이 다음 발화 응답 앞에 1회 prepend 후 소비된다</li>
 *   <li>useCallback 응답엔 prepend가 적용되지 않는다 → 본 케이스는 인스타 처리 흐름(스크래핑 의존)이라 unit으로 위임</li>
 * </ul>
 *
 * <p>자동 저장 백그라운드 처리(시나리오 C/D의 실제 candidates parse)는 인스타 스크래핑이
 * 외부 HTTPS 호출이라 e2e 비신뢰성. {@code PendingInstagramAutoSaveSchedulerTest} 및
 * {@code InstagramLinkHandler} 의 메서드 단위 테스트가 백그라운드 로직을 보장한다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class ChatbotInstagramMemoFlowIntegrationTest {

    private static final String SKILL_SECRET = "test-kakao-skill-secret";
    private static final String SKILL_HEADER = "X-Kakao-Skill-Secret";
    private static final String BOT_USER_KEY = "kakao-bot-user-memoflow-001";

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("place.instagram.scraping-enabled", () -> "false");
        registry.add("chatbot.rate-limit.capacity", () -> String.valueOf(Integer.MAX_VALUE));
        // 자동 저장 TTL은 1초로 단축하여 시나리오 C 단위 검증을 빠르게 (필요 시).
        registry.add("chatbot.instagram.pending-ttl-seconds", () -> "1");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BotUserMappingJpaRepository botUserMappingJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatbotRateLimiter rateLimiter;

    @Autowired
    private PendingInstagramSession pendingInstagramSession;

    @Autowired
    private PendingNotificationSession pendingNotificationSession;

    private Long userId;

    @BeforeEach
    void setUp() {
        truncate();
        pendingInstagramSession.invalidate(BOT_USER_KEY);
        pendingNotificationSession.invalidate(BOT_USER_KEY);
        rateLimiter.invalidateAll();

        UserModel saved = userJpaRepository.save(UserModel.create(2002002001L, "memoflow-tester", null));
        userId = saved.getId();
        Long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "memoflow-group"
        );
        jdbcTemplate.update(
                "INSERT INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, now())",
                groupId, userId
        );
        botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));
    }

    @AfterEach
    void tearDown() {
        pendingInstagramSession.invalidate(BOT_USER_KEY);
        pendingNotificationSession.invalidate(BOT_USER_KEY);
        truncate();
    }

    private void truncate() {
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        botUserMappingJpaRepository.deleteAll();
        userJpaRepository.deleteAll();
    }

    private ResponseEntity<JsonNode> post(String utterance) {
        String body = """
                {
                  "userRequest": {
                    "utterance": %s,
                    "user": { "id": "%s", "type": "botUserKey" }
                  },
                  "action": { "params": {} }
                }
                """.formatted(quote(utterance), BOT_USER_KEY);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SKILL_HEADER, SKILL_SECRET);
        return restTemplate.exchange(
                "/api/v1/chatbot/webhook",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String firstOutputText(JsonNode body) {
        JsonNode outputs = body.path("template").path("outputs");
        if (!outputs.isArray() || outputs.isEmpty()) return "";
        return outputs.get(0).path("simpleText").path("text").asText("");
    }

    private static String quickReplyMessageText(JsonNode body, int index) {
        return body.path("template").path("quickReplies").get(index).path("messageText").asText("");
    }

    @DisplayName("인스타 URL 수신 시 안내문 + QuickReply 1개('메모 없이 저장')를 반환한다.")
    @Test
    void instagramLink_responsePrompts_memoWithSingleQuickReply() {
        // act
        ResponseEntity<JsonNode> response = post("https://www.instagram.com/reel/MEMOFLOW1/");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = firstOutputText(response.getBody());
        assertThat(text)
                .as("안내문은 메모 요청 + 자동 저장 안내 3줄 형태")
                .contains("메모")
                .contains("자동")
                .contains("저장");

        JsonNode quickReplies = response.getBody().path("template").path("quickReplies");
        assertThat(quickReplies.isArray()).isTrue();
        assertThat(quickReplies).hasSize(1);
        assertThat(quickReplyMessageText(response.getBody(), 0))
                .as("QuickReply 전송값은 정확히 '메모 없이 저장'")
                .isEqualTo("메모 없이 저장");

        // pending 세션에 URL이 적재됨
        assertThat(pendingInstagramSession.peek(BOT_USER_KEY))
                .contains("https://www.instagram.com/reel/MEMOFLOW1/");
    }

    @DisplayName("PendingNotificationSession 에 알림이 적재된 상태에서 다음 발화 응답 outputs[0] 에 알림이 prepend 된다.")
    @Test
    void pendingNotification_prependedOnNextResponse() {
        // arrange: 자동 저장 결과가 적재된 상태를 직접 시뮬레이션
        String notice = "📌 이전에 보낸 링크는 메모 없이 자동 저장되었어요\n• 강남역";
        pendingNotificationSession.put(BOT_USER_KEY, notice);

        // act: 일반 텍스트(UNKNOWN) 발화
        ResponseEntity<JsonNode> response = post("안녕");

        // assert: outputs[0] = prepend 알림, outputs[1] = UNKNOWN 안내
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode outputs = response.getBody().path("template").path("outputs");
        assertThat(outputs.isArray()).isTrue();
        assertThat(outputs.size()).isGreaterThanOrEqualTo(2);
        assertThat(outputs.get(0).path("simpleText").path("text").asText())
                .isEqualTo(notice);
        assertThat(outputs.get(1).path("simpleText").path("text").asText())
                .contains("인스타그램");

        // notification 은 1회 소비됨
        assertThat(pendingNotificationSession.peek(BOT_USER_KEY)).isEmpty();
    }

    @DisplayName("prepend 가 1회 소비된 후 다시 발화하면 알림은 더 이상 붙지 않는다.")
    @Test
    void pendingNotification_consumedOnce() {
        // arrange
        pendingNotificationSession.put(BOT_USER_KEY, "📌 이전 알림");

        // act: 첫 번째 발화 — prepend 적용
        ResponseEntity<JsonNode> first = post("아무말");
        assertThat(first.getBody().path("template").path("outputs").size())
                .as("첫 응답엔 prepend + 본문 2개")
                .isGreaterThanOrEqualTo(2);

        // act: 두 번째 발화 — prepend 없음
        ResponseEntity<JsonNode> second = post("다시");

        // assert
        JsonNode outputs = second.getBody().path("template").path("outputs");
        assertThat(outputs.size())
                .as("두 번째 응답은 본문 1개만")
                .isEqualTo(1);
        assertThat(outputs.get(0).path("simpleText").path("text").asText())
                .as("📌 prefix 가 붙어있지 않음")
                .doesNotContain("📌");
    }
}
