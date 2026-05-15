package com.wherewego.interfaces.api.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.domain.bot.BotLinkCode;
import com.wherewego.domain.bot.BotLinkCodeService;
import com.wherewego.domain.bot.BotUserMapping;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.bot.BotLinkCodeJpaRepository;
import com.wherewego.infrastructure.bot.BotUserMappingJpaRepository;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chatbot Skill webhook E2E. WireMock 으로 Kakao Local 만 외부 의존성 대체.
 *
 * <p><b>설계 트레이드오프 — Instagram 스크래핑 우회 전략:</b>
 * 운영 spike 코드가 인스타그램 게시물에 실제 HTTPS 호출을 시도하므로 E2E 에서 안정적으로 mock 하기 어렵다.
 * 본 IT 는 {@code place.instagram.scraping-enabled=false} 로 덮어써 폴백 경로(AC-18) 만 검증하고,
 * Kakao Local 검색 분기(AC-9 Single, AC-10 Multiple)는 단위 테스트
 * {@code PlaceSearchServiceTest} 와 {@code InstagramContentServiceTest} 가 보장한다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class ChatbotV1ControllerIntegrationTest {

    private static final String SKILL_SECRET = "test-kakao-skill-secret";
    private static final String SKILL_HEADER = "X-Kakao-Skill-Secret";
    private static final String BOT_USER_KEY = "kakao-bot-user-001";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void overrideExternalUrls(DynamicPropertyRegistry registry) {
        // Kakao Local 만 WireMock 으로 대체. Instagram 스크래퍼는 feature flag 로 우회 (AC-18).
        registry.add("kakao.local.base-url", wireMock::baseUrl);
        registry.add("place.instagram.scraping-enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BotLinkCodeJpaRepository botLinkCodeJpaRepository;

    @Autowired
    private BotUserMappingJpaRepository botUserMappingJpaRepository;

    @Autowired
    private BotLinkCodeService botLinkCodeService;

    @Autowired
    private TwoSecondMemoSession twoSecondMemoSession;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;
    private Long groupId;

    @BeforeEach
    void cleanUp() {
        truncateAll();
        twoSecondMemoSession.invalidate(BOT_USER_KEY);
        wireMock.resetAll();

        UserModel saved = userJpaRepository.save(UserModel.create(2002002000L, "chatbot-tester", null));
        this.userId = saved.getId();
        this.groupId = jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "chatbot-group"
        );
        jdbcTemplate.update(
                "INSERT INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, now())",
                groupId, userId
        );
    }

    @AfterEach
    void tearDown() {
        truncateAll();
        twoSecondMemoSession.invalidate(BOT_USER_KEY);
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        botLinkCodeJpaRepository.deleteAll();
        botUserMappingJpaRepository.deleteAll();
        userJpaRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private ResponseEntity<JsonNode> webhookCall(String utterance, String skillSecret) {
        String body = """
                {
                  "userRequest": {
                    "utterance": %s,
                    "user": {
                      "id": "%s",
                      "type": "botUserKey"
                    }
                  },
                  "action": {
                    "params": {}
                  }
                }
                """.formatted(quote(utterance), BOT_USER_KEY);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (skillSecret != null) {
            headers.add(SKILL_HEADER, skillSecret);
        }
        return restTemplate.exchange(
                "/api/v1/chatbot/webhook",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String simpleText(JsonNode body) {
        JsonNode outputs = body.path("template").path("outputs");
        if (!outputs.isArray() || outputs.isEmpty()) {
            return "";
        }
        return outputs.get(0).path("simpleText").path("text").asText("");
    }

    private void stubKakaoLocalSingle(String placeId, String placeName) {
        String json = """
                {
                  "documents": [
                    {
                      "id": "%s",
                      "place_name": "%s",
                      "address_name": "주소",
                      "road_address_name": "도로명주소",
                      "x": "127.0",
                      "y": "37.5"
                    }
                  ]
                }
                """.formatted(placeId, placeName);
        wireMock.stubFor(get(urlPathEqualTo("/v2/local/search/keyword.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(json)));
    }

    // ------------------------------------------------------------------
    // 테스트
    // ------------------------------------------------------------------

    @DisplayName("POST /api/v1/chatbot/webhook - X-Kakao-Skill-Secret 헤더가 불일치하면 401 을 반환한다 (FR-BOT-9).")
    @Test
    void webhook_invalidSecret_returns401() {
        // act
        ResponseEntity<JsonNode> response = webhookCall("hello", "wrong-secret");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @DisplayName("POST /api/v1/chatbot/webhook - 미연동 사용자가 무효 6자리를 보내면 SimpleText 안내 메시지를 반환한다 (AC-5).")
    @Test
    void webhook_linkCode_invalid_returnsBotResponse() {
        // act : 매핑/발급 코드 둘 다 없음
        ResponseEntity<JsonNode> response = webhookCall("000000", SKILL_SECRET);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = simpleText(response.getBody());
        assertThat(text).contains("유효하지 않은 연동코드");
    }

    @DisplayName("POST /api/v1/chatbot/webhook - 정상 6자리 입력 시 bot_user_mappings 1건 생성 + '연동' 키워드 응답을 반환한다 (AC-4).")
    @Test
    void webhook_linkCode_valid_createsMappingAndReturnsSuccess() {
        // arrange : 사용자 발급 코드를 사전 INSERT
        String code = botLinkCodeService.issueCode(userId).code();
        BotLinkCode entity = botLinkCodeJpaRepository.findAll().get(0);
        assertThat(entity.getCode()).isEqualTo(code);

        // act
        ResponseEntity<JsonNode> response = webhookCall(code, SKILL_SECRET);

        // assert : 응답
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simpleText(response.getBody())).contains("연동");

        // assert : DB 매핑 1건
        List<BotUserMapping> mappings = botUserMappingJpaRepository.findAll();
        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).getUserId()).isEqualTo(userId);
        assertThat(mappings.get(0).getBotUserKey()).isEqualTo(BOT_USER_KEY);
    }

    @DisplayName("POST /api/v1/chatbot/webhook - 이미 매핑된 사용자가 또 6자리를 보내면 '이미 연동' 메시지를 반환한다 (AC-6).")
    @Test
    void webhook_linkCode_alreadyLinked_returnsAlreadyMessage() {
        // arrange : 매핑 사전 존재
        botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));

        // act
        ResponseEntity<JsonNode> response = webhookCall("123456", SKILL_SECRET);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simpleText(response.getBody())).contains("이미 연동");
    }

    @DisplayName("POST /api/v1/chatbot/webhook - 미연동 사용자가 인스타그램 URL 을 보내면 '먼저 연동' 안내를 반환한다 (AC-7).")
    @Test
    void webhook_instagramLink_notLinked_returnsLinkPrompt() {
        // act : 매핑 없는 botUserKey 로 인스타 URL
        ResponseEntity<JsonNode> response = webhookCall(
                "https://www.instagram.com/p/ABCDEF/",
                SKILL_SECRET
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simpleText(response.getBody())).contains("먼저");
        assertThat(simpleText(response.getBody())).contains("연동");
    }

    @DisplayName("POST /api/v1/chatbot/webhook - 매핑된 사용자가 인스타 URL 을 보내면 스크래핑 미사용(폴백)으로 안내 메시지를 반환한다 (AC-18).")
    @Test
    void webhook_instagramLink_scrapingDisabled_returnsFallback() {
        // arrange : 매핑 사전 존재 + scraping-enabled=false (@DynamicPropertySource)
        botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));

        // act
        ResponseEntity<JsonNode> response = webhookCall(
                "https://www.instagram.com/p/ABCDEF/",
                SKILL_SECRET
        );

        // assert : 스크래핑 비활성 → InstagramContentService.empty → 핸들러 폴백 SimpleText
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = simpleText(response.getBody());
        assertThat(text).contains("장소를 찾지 못했어요");

        // Pin 은 저장되지 않음
        Integer pinCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pins", Integer.class);
        assertThat(pinCount).isZero();
    }

    @DisplayName("POST /api/v1/chatbot/webhook - 매핑된 사용자가 일반 텍스트(미분류)를 보내면 UNKNOWN 안내(인스타 링크/연동 안내) 를 반환한다.")
    @Test
    void webhook_unknown_returnsUnknownGuide() {
        // arrange : 매핑 + 2초 메모 세션 없음 → UNKNOWN
        botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));

        // act
        ResponseEntity<JsonNode> response = webhookCall("그냥 잡담", SKILL_SECRET);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = simpleText(response.getBody());
        assertThat(text).contains("인스타그램");
    }

    @DisplayName("POST /api/v1/chatbot/webhook - 2초 메모 세션이 있는 상태에서 일반 텍스트가 들어오면 outputs 가 빈 응답을 반환한다 (AC-16).")
    @Test
    void webhook_twoSecondMemo_emptyResponse() {
        // arrange : 매핑 + 2초 메모 세션 사전 등록
        botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));
        // pin 1건 사전 INSERT (FK 만족 위한 더미)
        Long pinId = jdbcTemplate.queryForObject(
                "INSERT INTO pins (group_id, created_by, place_name, latitude, longitude, tag) "
                        + "VALUES (?, ?, ?, ?, ?, 'PLACE') RETURNING id",
                Long.class, groupId, userId, "더미장소", 37.5, 127.0
        );
        twoSecondMemoSession.put(BOT_USER_KEY, pinId);

        try {
            // act
            ResponseEntity<JsonNode> response = webhookCall("맛있어요", SKILL_SECRET);

            // assert : outputs 빈 배열 (SkillResponse.empty)
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode outputs = response.getBody().path("template").path("outputs");
            assertThat(outputs.isArray()).isTrue();
            assertThat(outputs).isEmpty();
        } finally {
            twoSecondMemoSession.invalidate(BOT_USER_KEY);
        }
    }

    @DisplayName("POST /api/v1/chatbot/webhook - Kakao Local 단건 결과 Pin 등록 (AC-9): 운영 spike 의 실제 HTTPS 호출 의존으로 E2E 비신뢰성 — 단위 테스트로 위임.")
    @Disabled("AC-9 single-hit Pin 등록은 Instagram 스크래핑(spike 실제 HTTPS) 의존으로 E2E 비신뢰성. PlaceSearchServiceTest 와 InstagramContentServiceTest 로 검증.")
    @Test
    void webhook_instagramLink_kakaoSingle_registersPin() {
        // 비활성 — 설계서 권장 옵션 (b) 에 따라 단위 테스트로 위임
        stubKakaoLocalSingle("kakao-1", "단건장소");
    }

    @DisplayName("POST /api/v1/chatbot/webhook - Kakao Local 다건 결과 BasicCard 응답 (AC-10): 동일 이유로 단위 테스트로 위임.")
    @Disabled("AC-10 multiple-hit BasicCard 응답은 Instagram 스크래핑(spike 실제 HTTPS) 의존으로 E2E 비신뢰성. PlaceSearchServiceTest 로 검증.")
    @Test
    void webhook_instagramLink_kakaoMultiple_returnsListCard() {
        // 비활성 — 설계서 권장 옵션 (b) 에 따라 단위 테스트로 위임
    }
}
