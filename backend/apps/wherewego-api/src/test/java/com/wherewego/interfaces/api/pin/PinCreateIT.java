package com.wherewego.interfaces.api.pin;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.user.UserJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/groups/{groupId}/pins} (Phase 6 FR-API-1) 통합 테스트.
 * <p>활성 그룹원 setup 은 Phase 3 흐름(create → invite → accept) 으로 구성한다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PinCreateIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userAId;
    private Long userBId;
    private Long userCId;
    private String tokenA;
    private String tokenB;
    private String tokenC;
    private Long groupId;

    @BeforeEach
    void cleanUp() {
        truncateAll();

        UserModel userA = userJpaRepository.save(UserModel.create(60000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(60000002L, "userB", null));
        UserModel userC = userJpaRepository.save(UserModel.create(60000003L, "userC", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.userCId = userC.getId();
        this.tokenA = jwtTokenProvider.issueAccessToken(userAId);
        this.tokenB = jwtTokenProvider.issueAccessToken(userBId);
        this.tokenC = jwtTokenProvider.issueAccessToken(userCId);

        JsonNode createBody = createGroup(tokenA, "지도팀").getBody();
        this.groupId = createBody.get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody()
                .get("data").get("token").asText();
        acceptInviteLink(tokenB, inviteToken);
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM invite_links");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.add(HttpHeaders.COOKIE, "access_token=" + accessToken);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<JsonNode> createGroup(String accessToken, String name) {
        String body = "{\"name\":\"" + name.replace("\"", "\\\"") + "\"}";
        return restTemplate.exchange(
                "/api/v1/groups",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> issueInviteLink(String accessToken, Long groupId) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/invite-links",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> acceptInviteLink(String accessToken, String token) {
        return restTemplate.exchange(
                "/api/v1/groups/invite-links/" + token + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> createPin(String accessToken, Long groupId, String body) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/pins",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(accessToken)),
                JsonNode.class);
    }

    @DisplayName("POST /api/v1/groups/{groupId}/pins - 활성 멤버가 정상 입력으로 등록하면 201 과 PinSummaryResponse 를 받는다.")
    @Test
    void createPin_success_returnsCreatedPin() {
        // arrange
        String body = "{"
                + "\"placeName\":\"성수동 카페\","
                + "\"address\":\"서울 성동구 성수동\","
                + "\"latitude\":37.5443,"
                + "\"longitude\":127.0557,"
                + "\"memo\":\"분위기 좋음\","
                + "\"tag\":\"PLACE\""
                + "}";

        // act
        ResponseEntity<JsonNode> response = createPin(tokenA, groupId, body);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("placeName").asText()).isEqualTo("성수동 카페");
        assertThat(data.get("address").asText()).isEqualTo("서울 성동구 성수동");
        assertThat(data.get("tag").asText()).isEqualTo("PLACE");
        assertThat(data.get("memo").asText()).isEqualTo("분위기 좋음");
        assertThat(data.get("memoSource").asText()).isEqualTo("MANUAL");
        assertThat(data.get("createdBy").asLong()).isEqualTo(userAId);
        assertThat(data.get("groupId").asLong()).isEqualTo(groupId);
    }

    @DisplayName("POST - 비멤버는 403 GROUP_NOT_MEMBER 를 반환한다.")
    @Test
    void createPin_notMember_returns403() {
        // arrange
        String body = "{"
                + "\"placeName\":\"P\","
                + "\"latitude\":37.5,"
                + "\"longitude\":127.0,"
                + "\"tag\":\"PLACE\""
                + "}";

        // act : userC 는 비멤버
        ResponseEntity<JsonNode> response = createPin(tokenC, groupId, body);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("GROUP_NOT_MEMBER");
    }

    @DisplayName("POST - tag 누락 시 400 PIN_TAG_INVALID 를 반환한다.")
    @Test
    void createPin_invalidTag_returns400() {
        // arrange : tag 필드 자체 누락
        String body = "{"
                + "\"placeName\":\"P\","
                + "\"latitude\":37.5,"
                + "\"longitude\":127.0"
                + "}";

        // act
        ResponseEntity<JsonNode> response = createPin(tokenA, groupId, body);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_TAG_INVALID");
    }

    @DisplayName("POST - placeName 이 공백이면 400 PIN_PLACE_NAME_INVALID 를 반환한다.")
    @Test
    void createPin_invalidPlaceName_returns400() {
        // arrange
        String body = "{"
                + "\"placeName\":\"   \","
                + "\"latitude\":37.5,"
                + "\"longitude\":127.0,"
                + "\"tag\":\"PLACE\""
                + "}";

        // act
        ResponseEntity<JsonNode> response = createPin(tokenA, groupId, body);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PLACE_NAME_INVALID");
    }

    @DisplayName("POST - 위도 범위를 벗어나면 400 PIN_COORDINATE_INVALID 를 반환한다.")
    @Test
    void createPin_invalidCoordinate_returns400() {
        // arrange : 위도 91도 (허용 범위 초과)
        String body = "{"
                + "\"placeName\":\"P\","
                + "\"latitude\":91.0,"
                + "\"longitude\":127.0,"
                + "\"tag\":\"PLACE\""
                + "}";

        // act
        ResponseEntity<JsonNode> response = createPin(tokenA, groupId, body);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_COORDINATE_INVALID");
    }

    @DisplayName("POST - memo 가 501자 이상이면 400 PIN_MEMO_TOO_LONG 을 반환한다.")
    @Test
    void createPin_memoTooLong_returns400() {
        // arrange
        String tooLong = "a".repeat(501);
        String body = "{"
                + "\"placeName\":\"P\","
                + "\"latitude\":37.5,"
                + "\"longitude\":127.0,"
                + "\"memo\":\"" + tooLong + "\","
                + "\"tag\":\"PLACE\""
                + "}";

        // act
        ResponseEntity<JsonNode> response = createPin(tokenA, groupId, body);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_MEMO_TOO_LONG");
    }

    @DisplayName("POST - instagramUrl 중복은 409 PLC_DUPLICATE_PIN 을 반환한다.")
    @Test
    void createPin_duplicateInstagram_returns409() {
        // arrange : 첫 핀 등록 성공
        String first = "{"
                + "\"placeName\":\"P1\","
                + "\"latitude\":37.5,"
                + "\"longitude\":127.0,"
                + "\"instagramUrl\":\"https://www.instagram.com/p/DUP/\","
                + "\"tag\":\"PLACE\""
                + "}";
        assertThat(createPin(tokenA, groupId, first).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // act : 같은 instagramUrl 로 다시 등록
        String second = "{"
                + "\"placeName\":\"P2\","
                + "\"latitude\":37.6,"
                + "\"longitude\":127.1,"
                + "\"instagramUrl\":\"https://www.instagram.com/p/DUP/\","
                + "\"tag\":\"PLACE\""
                + "}";
        ResponseEntity<JsonNode> response = createPin(tokenA, groupId, second);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PLC_DUPLICATE_PIN");
    }

    @DisplayName("POST - instagramUrl 이 없으면 같은 좌표여도 여러 건 등록 가능하다 (BR-3).")
    @Test
    void createPin_noInstagramUrl_allowsMultiple() {
        // arrange
        String body = "{"
                + "\"placeName\":\"P\","
                + "\"latitude\":37.5,"
                + "\"longitude\":127.0,"
                + "\"tag\":\"PLACE\""
                + "}";

        // act : 동일 입력 2회 등록
        ResponseEntity<JsonNode> first = createPin(tokenA, groupId, body);
        ResponseEntity<JsonNode> second = createPin(tokenA, groupId, body);

        // assert : 둘 다 성공
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("data").get("id").asLong())
                .isNotEqualTo(second.getBody().get("data").get("id").asLong());
    }
}
