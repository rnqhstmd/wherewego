package com.wherewego.interfaces.api.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.config.security.ChatbotRateLimiter;
import com.wherewego.domain.bot.BotLinkCode;
import com.wherewego.domain.bot.BotLinkCodeService;
import com.wherewego.domain.bot.BotUserMapping;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.PlaceSelectionCandidateStore;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.bot.BotLinkCodeJpaRepository;
import com.wherewego.infrastructure.bot.BotUserMappingJpaRepository;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
        // Kakao Local 과 Google Places 모두 WireMock 으로 대체.
        // Instagram 스크래퍼는 feature flag 로 우회 (AC-18) — 외부 HTTPS 호출 비신뢰성 차단.
        registry.add("kakao.local.base-url", wireMock::baseUrl);
        registry.add("google.places.base-url", wireMock::baseUrl);
        registry.add("place.instagram.scraping-enabled", () -> "false");
        // Phase 2.6 PR-B B-3: 본 IT 는 webhook 핸들러 로직 회귀 방지가 목적이므로 레이트 리밋을 사실상 무한으로.
        registry.add("chatbot.rate-limit.capacity", () -> String.valueOf(Integer.MAX_VALUE));
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

    @Autowired
    private ChatbotRateLimiter rateLimiter;

    @Autowired
    private PlaceSelectionCandidateStore placeSelectionCandidateStore;

    private Long userId;
    private Long groupId;

    @BeforeEach
    void cleanUp() {
        truncateAll();
        twoSecondMemoSession.invalidate(BOT_USER_KEY);
        wireMock.resetAll();
        // Phase 2.6 PR-B B-3: 테스트 간 레이트 리밋 카운터 격리.
        rateLimiter.invalidateAll();

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
        // Phase 10 V009: notifications.visit_pin_id → pins(id) FK 추가로 인해 pins 보다 먼저 삭제 필요.
        jdbcTemplate.execute("DELETE FROM notification_pins");
        jdbcTemplate.execute("DELETE FROM notifications");
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

    // ------------------------------------------------------------------
    // PLACE_SELECTION 헬퍼 (Phase 2.7 영역 1)
    // ------------------------------------------------------------------

    /**
     * PLACE_SELECTION 흐름의 webhook 호출. {@code action.params.placeId} 본문 JSON 에 포함.
     * MessageClassifier 가 placeId 비공백 시 PLACE_SELECTION 으로 분기 (clientExtra 우선, params 폴백).
     */
    private ResponseEntity<JsonNode> webhookCallWithPlaceId(
            String botUserKey, String placeId, String skillSecret) {
        String body = """
                {
                  "userRequest": {
                    "utterance": "1",
                    "user": {
                      "id": "%s",
                      "type": "botUserKey"
                    }
                  },
                  "action": {
                    "params": {
                      "placeId": %s
                    },
                    "clientExtra": {
                      "placeId": %s
                    }
                  }
                }
                """.formatted(botUserKey, quote(placeId), quote(placeId));

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

    /**
     * PlaceSelectionCandidateStore 에 후보 1건을 사전 적재.
     * {@code Entry(PlaceSearchHit hit, String instagramUrl)} 2필드 record; placeId 는 캐시 키로만 사용.
     */
    private void seedSelectionCandidate(String botUserKey, String placeId, String placeName) {
        PlaceSearchHit hit = new PlaceSearchHit(placeId, placeName, "서울 강남구", 37.5, 127.0);
        placeSelectionCandidateStore.put(
                botUserKey,
                placeId,
                new PlaceSelectionCandidateStore.Entry(hit, "https://www.instagram.com/p/X/")
        );
    }

    // ------------------------------------------------------------------
    // PLACE_SELECTION E2E (Phase 2.7 영역 1, AC-1~AC-5)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("PLACE_SELECTION 분기 E2E")
    class PlaceSelection {

        @DisplayName("(AC-1) 연동 + 그룹 가입 사용자가 적재된 후보 placeId 로 webhook → HTTP 200 + '장소가 저장되었어요' + pins 1건 삽입")
        @Test
        void placeSelection_normal_registersPin() {
            // given : 매핑 + 활성 group_members 시드(BeforeEach 재활용) + 후보 적재
            botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));
            seedSelectionCandidate(BOT_USER_KEY, "kakao-1", "테스트장소");

            // when
            ResponseEntity<JsonNode> response = webhookCallWithPlaceId(BOT_USER_KEY, "kakao-1", SKILL_SECRET);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(simpleText(response.getBody())).contains("장소가 저장되었어요");
            Integer pinCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pins", Integer.class);
            assertThat(pinCount).isEqualTo(1);
        }

        @DisplayName("(AC-2) 후보 없는 placeId 로 webhook → HTTP 200 + '선택 시간이 만료되었어요' + pins 무삽입")
        @Test
        void placeSelection_noCandidate_returnsExpiredMessage() {
            // given : 매핑 + 활성 group_members 시드(BeforeEach 재활용), store 미적재
            botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));

            // when
            ResponseEntity<JsonNode> response =
                    webhookCallWithPlaceId(BOT_USER_KEY, "non-existing", SKILL_SECRET);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(simpleText(response.getBody())).contains("선택 시간이 만료되었어요");
            Integer pinCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pins", Integer.class);
            assertThat(pinCount).isZero();
        }

        @DisplayName("(AC-3) 봇 미연동 사용자의 PLACE_SELECTION 요청 → HTTP 200 + '연동코드' 안내")
        @Test
        void placeSelection_notLinked_returnsLinkCodeGuide() {
            // given : BotUserMapping 미적재
            // (ChatbotWebhookService 가 PLACE_SELECTION 진입 전 미연동 가드에서 즉시 차단)

            // when
            ResponseEntity<JsonNode> response =
                    webhookCallWithPlaceId(BOT_USER_KEY, "kakao-1", SKILL_SECRET);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(simpleText(response.getBody())).contains("연동코드");
        }

        @DisplayName("(AC-4) 연동 + 그룹 미가입 사용자의 PLACE_SELECTION 요청 → HTTP 200 + '그룹에 먼저 참여해주세요'")
        @Test
        void placeSelection_noGroupMembership_returnsJoinGroupMessage() {
            // given : 매핑 적재 + BeforeEach 가 시드한 group_members 제거 + store 적재
            botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));
            jdbcTemplate.update("DELETE FROM group_members WHERE user_id = ?", userId);
            seedSelectionCandidate(BOT_USER_KEY, "kakao-1", "테스트장소");

            // when
            ResponseEntity<JsonNode> response =
                    webhookCallWithPlaceId(BOT_USER_KEY, "kakao-1", SKILL_SECRET);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(simpleText(response.getBody())).contains("그룹에 먼저 참여해주세요");
        }

        @DisplayName("(AC-5) 이미 저장된 장소와 동일한 후보 → HTTP 200 + '📌 이미 저장된 장소' 통합 포맷")
        @Test
        void placeSelection_duplicatePin_returnsAlreadySavedMessage() {
            // given : 매핑 + 활성 group_members(BeforeEach) + 동일 (group_id, instagram_url, place_name) 사전 INSERT + store 적재
            // 중복 검증은 uq_pins_group_instagram_place 제약(group_id, instagram_url, place_name)을 트리거하여 발생.
            botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));
            jdbcTemplate.update(
                    "INSERT INTO pins (group_id, created_by, place_name, latitude, longitude, instagram_url, tag) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'PLACE')",
                    groupId, userId, "테스트장소", 37.5, 127.0, "https://www.instagram.com/p/X/"
            );
            seedSelectionCandidate(BOT_USER_KEY, "kakao-1", "테스트장소");

            // when
            ResponseEntity<JsonNode> response =
                    webhookCallWithPlaceId(BOT_USER_KEY, "kakao-1", SKILL_SECRET);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(simpleText(response.getBody())).contains("📌 이미 저장된 장소");
            assertThat(simpleText(response.getBody())).contains("테스트장소");
        }
    }

    // ------------------------------------------------------------------
    // 구글 폴백 (Phase 5)
    // ------------------------------------------------------------------

    private void stubKakaoLocalEmpty() {
        String json = """
                { "documents": [] }
                """;
        wireMock.stubFor(get(urlPathEqualTo("/v2/local/search/keyword.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(json)));
    }

    private void stubGooglePlacesSingle(String placeId, String placeName) {
        String json = """
                {
                  "places": [
                    {
                      "id": "%s",
                      "displayName": { "text": "%s", "languageCode": "ko" },
                      "formattedAddress": "주소",
                      "location": { "latitude": 37.5, "longitude": 127.0 }
                    }
                  ]
                }
                """.formatted(placeId, placeName);
        wireMock.stubFor(post(urlPathEqualTo("/v1/places:searchText"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(json)));
    }

    private void stubGooglePlacesServerError() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/places:searchText"))
                .willReturn(aResponse().withStatus(500)));
    }

    /**
     * Google 폴백 동기 경로 E2E.
     *
     * <p><b>비활성 사유 — Instagram 스크래핑 의존:</b>
     * 본 동기 폴백 경로는 카카오 Local 검색 분기와 동일하게 Instagram 스크래퍼가 실제 키워드를 추출해야 진입한다.
     * Instagram 게시물 페이지는 외부 HTTPS 호출이며 WireMock 으로 인터셉트할 수 없어 E2E 가 flaky 하다
     * (기존 AC-9/AC-10 케이스도 동일 사유로 {@code @Disabled} 처리됨).
     * 본 경로의 핵심 분기(runSync Single/Multiple/Empty + Slack 폴백)는
     * {@code PlaceFallbackOrchestratorTest} 단위 테스트가 8 케이스로 커버한다.</p>
     */
    @Nested
    @DisplayName("Google 폴백 경로 (동기) E2E")
    class GoogleFallbackSync {

        @DisplayName("POST /api/v1/chatbot/webhook - 카카오 Local Empty + Google 200 Single 시 동기 폴백으로 저장 메시지를 응답한다.")
        @Disabled("Instagram 스크래핑(spike 실제 HTTPS) 의존으로 E2E 비신뢰성. PlaceFallbackOrchestratorTest.runSync_singleHit 로 검증.")
        @Test
        void googleFallback_singleHit_returnsSavedMessage() {
            // arrange : 카카오는 0건, Google 은 1건 — 동기 폴백 Single 진입.
            botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));
            stubKakaoLocalEmpty();
            stubGooglePlacesSingle("g1", "구글단건장소");

            // act
            ResponseEntity<JsonNode> response = webhookCall(
                    "https://www.instagram.com/p/GFB-1/",
                    SKILL_SECRET
            );

            // assert : 단건 저장 안내
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(simpleText(response.getBody())).contains("장소가 저장되었어요");
        }

        @DisplayName("POST /api/v1/chatbot/webhook - 카카오 Local Empty + Google 5xx 시 동기 폴백 실패로 Empty 메시지를 응답한다.")
        @Disabled("Instagram 스크래핑(spike 실제 HTTPS) 의존으로 E2E 비신뢰성. PlaceFallbackOrchestratorTest.runSync_googleFailure 로 검증.")
        @Test
        void googleFallback_googleServerError_returnsEmptyMessage() {
            // arrange : 카카오는 0건, Google 은 500 — 동기 폴백 Empty + Slack 알림.
            botUserMappingJpaRepository.save(BotUserMapping.link(BOT_USER_KEY, userId, Instant.now()));
            stubKakaoLocalEmpty();
            stubGooglePlacesServerError();

            // act
            ResponseEntity<JsonNode> response = webhookCall(
                    "https://www.instagram.com/p/GFB-2/",
                    SKILL_SECRET
            );

            // assert : 폴백 SimpleText 안내
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(simpleText(response.getBody())).contains("장소를 찾지 못했어요");
        }
    }
}
