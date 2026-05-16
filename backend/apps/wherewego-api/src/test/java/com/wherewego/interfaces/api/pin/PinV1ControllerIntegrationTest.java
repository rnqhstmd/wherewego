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

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PinV1ControllerIntegrationTest {

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

        UserModel userA = userJpaRepository.save(UserModel.create(40000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(40000002L, "userB", null));
        UserModel userC = userJpaRepository.save(UserModel.create(40000003L, "userC", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.userCId = userC.getId();
        this.tokenA = jwtTokenProvider.issueAccessToken(userAId);
        this.tokenB = jwtTokenProvider.issueAccessToken(userBId);
        this.tokenC = jwtTokenProvider.issueAccessToken(userCId);

        // userA 가 그룹 생성 + userB 가 초대 수락 (활성 멤버 2명)
        JsonNode createBody = createGroup(tokenA, "여행팀").getBody();
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

    private ResponseEntity<JsonNode> listPins(String accessToken, Long groupId, String tag) {
        String path = "/api/v1/groups/" + groupId + "/pins" + (tag == null ? "" : "?tag=" + tag);
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> patchPin(String accessToken, Long groupId, Long pinId, String body) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/pins/" + pinId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> deletePin(String accessToken, Long groupId, Long pinId) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/pins/" + pinId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    private Long insertPin(Long groupId, Long createdBy, String placeName,
                           String instagramUrl, String memo, String memoSource, String tag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO pins (group_id, created_by, place_name, address, latitude, longitude, "
                        + "instagram_url, memo, memo_source, tag) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                groupId, createdBy, placeName, "서울 강남구",
                new BigDecimal("37.5000000"), new BigDecimal("127.0000000"),
                instagramUrl, memo, memoSource, tag);
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins - 활성 멤버는 200 과 items 배열을 받는다 (AC-1).")
    @Test
    void listPins_activeMember_returns200WithItems() {
        // arrange : 2개 핀 (둘 다 PLACE)
        insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/I1/", null, null, "PLACE");
        insertPin(groupId, userAId, "P2", "https://www.instagram.com/p/I2/", null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = listPins(tokenA, groupId, null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");
        JsonNode items = body.get("data").get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(2);
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?tag=PLACE - tag 필터가 동작한다 (AC-2).")
    @Test
    void listPins_withTagFilter_returnsFiltered() {
        // arrange : PLACE 1 + MEMORY 1
        insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/T1/", null, null, "PLACE");
        insertPin(groupId, userAId, "M1", "https://www.instagram.com/p/T2/", null, null, "MEMORY");

        // act
        ResponseEntity<JsonNode> response = listPins(tokenA, groupId, "PLACE");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = response.getBody().get("data").get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("tag").asText()).isEqualTo("PLACE");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?tag=INVALID - 400 PIN_TAG_INVALID 를 반환한다 (AC-2 일관성).")
    @Test
    void listPins_invalidTag_returnsPinTagInvalid() {
        // arrange : 그룹은 이미 setup 됨

        // act
        ResponseEntity<JsonNode> response = listPins(tokenA, groupId, "INVALID");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_TAG_INVALID");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins - 비멤버는 403 GROUP_NOT_MEMBER 를 반환한다 (AC-3).")
    @Test
    void listPins_nonMember_returns403() {
        // arrange : userC 는 그룹 비멤버
        // act
        ResponseEntity<JsonNode> response = listPins(tokenC, groupId, null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("GROUP_NOT_MEMBER");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - memo+tag 동시 전달 시 200 과 갱신 결과를 반환한다 (AC-6).")
    @Test
    void patchPin_memoAndTag_returns200() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/P1/",
                null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"memo\": \"hi\", \"tag\": \"MEMORY\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("memo").asText()).isEqualTo("hi");
        assertThat(data.get("memoSource").asText()).isEqualTo("MANUAL");
        assertThat(data.get("tag").asText()).isEqualTo("MEMORY");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 빈 body 는 400 PIN_UPDATE_EMPTY 를 반환한다 (AC-9).")
    @Test
    void patchPin_emptyBody_returns400() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/P2/",
                null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId, "{}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_UPDATE_EMPTY");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 501자 memo 는 400 PIN_MEMO_TOO_LONG 을 반환한다 (AC-12).")
    @Test
    void patchPin_memoTooLong_returns400() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/P3/",
                null, null, "PLACE");
        String tooLong = "a".repeat(501);

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"memo\": \"" + tooLong + "\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_MEMO_TOO_LONG");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 잘못된 tag 는 400 PIN_TAG_INVALID 를 반환한다 (AC-13).")
    @Test
    void patchPin_invalidTag_returns400() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/P4/",
                null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"tag\": \"INVALID\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_TAG_INVALID");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 빈 문자열 memo 는 DB 의 memo=NULL, memo_source=NULL 로 잠금 해제된다 (AC-11).")
    @Test
    void patchPin_emptyMemo_clearsLockInDb() {
        // arrange : MANUAL 메모를 가진 핀 직접 INSERT
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/E1/",
                "manual memo before clear", "MANUAL", "PLACE");

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId, "{\"memo\": \"\"}");

        // assert HTTP
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // assert DB
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT memo, memo_source FROM pins WHERE id = ?", pinId);
        assertThat(row.get("memo")).isNull();
        assertThat(row.get("memo_source")).isNull();
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 삭제된 핀은 404 PIN_NOT_FOUND 를 반환한다 (AC-14).")
    @Test
    void patchPin_deletedPin_returns404() {
        // arrange : 핀 INSERT 직후 직접 soft delete
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/P5/",
                null, null, "PLACE");
        jdbcTemplate.update("UPDATE pins SET deleted_at = now() WHERE id = ?", pinId);

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"memo\": \"x\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_NOT_FOUND");
    }

    @DisplayName("DELETE /api/v1/groups/{groupId}/pins/{pinId} - 204 No Content 후 목록에서 미반환된다 (AC-16).")
    @Test
    void deletePin_returns204AndRemovesFromListing() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/D1/",
                null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = deletePin(tokenA, groupId, pinId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 이후 목록 미반환
        JsonNode items = listPins(tokenA, groupId, null).getBody().get("data").get("items");
        assertThat(items).isEmpty();
    }

    @DisplayName("DELETE /api/v1/groups/{groupId}/pins/{pinId} - 이중 삭제는 404 PIN_NOT_FOUND 를 반환한다 (AC-17).")
    @Test
    void deletePin_doubleDelete_returns404() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/D2/",
                null, null, "PLACE");
        deletePin(tokenA, groupId, pinId);

        // act
        ResponseEntity<JsonNode> response = deletePin(tokenA, groupId, pinId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_NOT_FOUND");
    }

    @DisplayName("DELETE /api/v1/groups/{groupId}/pins/{pinId} - 비멤버는 403 GROUP_NOT_MEMBER 를 반환한다 (AC-18).")
    @Test
    void deletePin_nonMember_returns403() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/D3/",
                null, null, "PLACE");

        // act : userC 는 비멤버
        ResponseEntity<JsonNode> response = deletePin(tokenC, groupId, pinId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("GROUP_NOT_MEMBER");
    }
}
