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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

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

    private ResponseEntity<JsonNode> listPinsRaw(String accessToken, Long groupId, String query) {
        String path = "/api/v1/groups/" + groupId + "/pins" + (query == null || query.isEmpty() ? "" : "?" + query);
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

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - placeName 만 전달하면 200 과 갱신된 placeName 을 반환한다 (Phase 2.8).")
    @Test
    void patchPin_placeNameOnly_returns200WithUpdatedPlaceName() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/PN1/",
                null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"placeName\": \"새 이름\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("placeName").asText()).isEqualTo("새 이름");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 빈 placeName 은 400 PIN_PLACE_NAME_INVALID 를 반환한다 (Phase 2.8).")
    @Test
    void patchPin_emptyPlaceName_returnsPinPlaceNameInvalid() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/PN2/",
                null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"placeName\": \"\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PLACE_NAME_INVALID");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 빈 address 는 200 + address 미변경 (Q5 안전 무시).")
    @Test
    void patchPin_emptyAddress_returns200WithUnchangedAddress() {
        // arrange : 기존 address = "서울 강남구"
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/PN3/",
                null, null, "PLACE");

        // act : 빈 address + non-empty memo 조합 → address 미변경, memo 반영
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"memo\": \"m\", \"address\": \"\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("address").asText()).isEqualTo("서울 강남구");
        assertThat(data.get("memo").asText()).isEqualTo("m");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 빈 address 단독은 400 PIN_UPDATE_EMPTY 를 반환한다 (Phase 2.8 정규화).")
    @Test
    void patchPin_addressOnlyEmpty_returns400PinUpdateEmpty() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/PN6/",
                null, null, "PLACE");

        // act : 빈 address 단독 → addressProvided=false 로 정규화되어 수정 필드 없음
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"address\": \"\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_UPDATE_EMPTY");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - placeName 과 memo 동시 전달 시 양쪽이 반영된다 (Phase 2.8).")
    @Test
    void patchPin_placeNameAndMemo_returns200WithBothApplied() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/PN4/",
                null, null, "PLACE");

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"placeName\": \"x\", \"memo\": \"y\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("placeName").asText()).isEqualTo("x");
        assertThat(data.get("memo").asText()).isEqualTo("y");
        assertThat(data.get("memoSource").asText()).isEqualTo("MANUAL");
    }

    @DisplayName("PATCH /api/v1/groups/{groupId}/pins/{pinId} - 501자 address 는 400 PIN_ADDRESS_INVALID 를 반환한다 (Phase 2.8).")
    @Test
    void patchPin_addressTooLong_returnsPinAddressInvalid() {
        // arrange
        Long pinId = insertPin(groupId, userAId, "P1", "https://www.instagram.com/p/PN5/",
                null, null, "PLACE");
        String tooLong = "a".repeat(501);

        // act
        ResponseEntity<JsonNode> response = patchPin(tokenA, groupId, pinId,
                "{\"address\": \"" + tooLong + "\"}");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_ADDRESS_INVALID");
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

    /**
     * 페이지네이션 테스트용 25핀(PLACE 15 + MEMORY 10) 일괄 적재.
     * createdAt 순서를 보장하기 위해 INSERT 사이에 짧은 sleep 을 둔다.
     */
    private void seedTwentyFivePins() throws InterruptedException {
        for (int i = 0; i < 15; i++) {
            insertPin(groupId, userAId, "P" + i,
                    "https://www.instagram.com/p/PG" + i + "/", null, null, "PLACE");
            Thread.sleep(1);
        }
        for (int i = 0; i < 10; i++) {
            insertPin(groupId, userAId, "M" + i,
                    "https://www.instagram.com/p/MG" + i + "/", null, null, "MEMORY");
            Thread.sleep(1);
        }
    }

    /**
     * 페이지네이션 tie-breaker 검증용 25핀 적재. Thread.sleep 을 호출하지 않아
     * 동일 ms 에 createdAt 이 떨어질 수 있는 환경을 시뮬레이션한다 (AC-6 tie-breaker).
     */
    private void seedTwentyFivePinsWithoutSleep() {
        for (int i = 0; i < 25; i++) {
            insertPin(groupId, userAId, "T" + i,
                    "https://www.instagram.com/p/TB" + i + "/", null, null, "PLACE");
        }
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins - 동일 createdAt 가능 환경에서도 page 0/1 은 disjoint (AC-6 tie-breaker).")
    @Test
    void listPins_pagination_tieBreaker_disjoint_AC6() {
        // arrange : sleep 없이 25핀을 즉시 INSERT → 동일 ms createdAt 가능
        seedTwentyFivePinsWithoutSleep();

        // act : page=0/1, size=10
        ResponseEntity<JsonNode> first = listPinsRaw(tokenA, groupId, "page=0&size=10");
        ResponseEntity<JsonNode> second = listPinsRaw(tokenA, groupId, "page=1&size=10");

        // assert
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        Set<Long> firstIds = new HashSet<>();
        for (JsonNode item : first.getBody().get("data").get("items")) {
            firstIds.add(item.get("id").asLong());
        }
        Set<Long> secondIds = new HashSet<>();
        for (JsonNode item : second.getBody().get("data").get("items")) {
            secondIds.add(item.get("id").asLong());
        }

        // 교집합 없음 (disjoint)
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);

        // 합집합 크기 = 두 페이지 합 (중복/누락 없음)
        Set<Long> union = new HashSet<>(firstIds);
        union.addAll(secondIds);
        assertThat(union).hasSize(firstIds.size() + secondIds.size());
        // 25개 중 page 0/1 합 20개 도달 (동일 ms 환경에서도 tie-breaker 가 안정 정렬 보장)
        assertThat(union).hasSize(20);
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins - 파라미터 없음(legacy) 응답은 items 키만 가진다 (AC-0 케이스1, AC-2).")
    @Test
    void listPins_legacyMode_returnsItemsOnly_AC0_AC2() throws InterruptedException {
        // arrange
        seedTwentyFivePins();

        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        // AC-0 케이스1: totalCount/hasNext 미직렬화
        assertThat(data.has("totalCount")).isFalse();
        assertThat(data.has("hasNext")).isFalse();
        // AC-2: data.fieldNames() 가 {"items"} 단일 원소
        Iterator<String> it = data.fieldNames();
        Set<String> keys = new HashSet<>();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        assertThat(keys).containsExactlyInAnyOrder("items");
        // 25개 모두 반환 (legacy 는 무제한)
        assertThat(data.get("items")).hasSize(25);
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=0&size=20 - 페이지 응답은 totalCount/hasNext 포함, items 최대 20 (AC-0 케이스2, AC-1).")
    @Test
    void listPins_pageMode_returnsItemsAndMeta_AC0_AC1() throws InterruptedException {
        // arrange
        seedTwentyFivePins();

        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "page=0&size=20");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        // AC-0 케이스2: totalCount/hasNext 직렬화 존재
        assertThat(data.has("totalCount")).isTrue();
        assertThat(data.has("hasNext")).isTrue();
        // AC-1: 타입 검증
        assertThat(data.get("totalCount").isIntegralNumber()).isTrue();
        assertThat(data.get("hasNext").isBoolean()).isTrue();
        // items 최대 20
        JsonNode items = data.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isLessThanOrEqualTo(20);
        // totalCount 25, hasNext true (25 > 20)
        assertThat(data.get("totalCount").asLong()).isEqualTo(25L);
        assertThat(data.get("hasNext").asBoolean()).isTrue();
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=0&size=101 - 400 PIN_PAGE_SIZE_EXCEEDED 를 반환한다 (AC-3).")
    @Test
    void listPins_sizeExceeded_returns400_AC3() {
        // arrange : 그룹은 이미 setup 됨

        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "page=0&size=101");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PAGE_SIZE_EXCEEDED");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=-1&size=20 - 400 PIN_PAGE_PARAM_INVALID 를 반환한다 (AC-4).")
    @Test
    void listPins_invalidPageParam_returns400_AC4_negativePage() {
        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "page=-1&size=20");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PAGE_PARAM_INVALID");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=0&size=0 - 400 PIN_PAGE_PARAM_INVALID 를 반환한다 (AC-4).")
    @Test
    void listPins_invalidPageParam_returns400_AC4_zeroSize() {
        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "page=0&size=0");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PAGE_PARAM_INVALID");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=0 - size 누락은 400 PIN_PAGE_PARAM_INVALID 를 반환한다 (AC-4).")
    @Test
    void listPins_invalidPageParam_returns400_AC4_pageOnly() {
        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "page=0");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PAGE_PARAM_INVALID");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?size=20 - page 누락은 400 PIN_PAGE_PARAM_INVALID 를 반환한다 (AC-4).")
    @Test
    void listPins_invalidPageParam_returns400_AC4_sizeOnly() {
        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "size=20");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PAGE_PARAM_INVALID");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?tag=PLACE&page=0&size=10 - totalCount 는 PLACE 전체 수, items ≤ 10 (AC-5).")
    @Test
    void listPins_pageMode_withTagFilter_AC5() throws InterruptedException {
        // arrange
        seedTwentyFivePins();

        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "tag=PLACE&page=0&size=10");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("totalCount").asLong()).isEqualTo(15L);
        JsonNode items = data.get("items");
        assertThat(items.size()).isLessThanOrEqualTo(10);
        // 모두 PLACE 태그
        for (JsonNode item : items) {
            assertThat(item.get("tag").asText()).isEqualTo("PLACE");
        }
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=0|1&size=N - 페이지 간 id 집합 disjoint + 합집합 크기 일치 (AC-6).")
    @Test
    void listPins_pagination_noOverlap_AC6() throws InterruptedException {
        // arrange
        seedTwentyFivePins();
        int pageSize = 10;

        // act
        ResponseEntity<JsonNode> first = listPinsRaw(tokenA, groupId, "page=0&size=" + pageSize);
        ResponseEntity<JsonNode> second = listPinsRaw(tokenA, groupId, "page=1&size=" + pageSize);

        // assert
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        Set<Long> firstIds = new HashSet<>();
        for (JsonNode item : first.getBody().get("data").get("items")) {
            firstIds.add(item.get("id").asLong());
        }
        Set<Long> secondIds = new HashSet<>();
        for (JsonNode item : second.getBody().get("data").get("items")) {
            secondIds.add(item.get("id").asLong());
        }

        // disjoint: 교집합 없음
        Set<Long> intersection = new HashSet<>(firstIds);
        intersection.retainAll(secondIds);
        assertThat(intersection).isEmpty();

        // 합집합 크기 = 각 페이지 크기 합 (중복 없으므로)
        Set<Long> union = new HashSet<>(firstIds);
        union.addAll(secondIds);
        assertThat(union).hasSize(firstIds.size() + secondIds.size());

        // totalCount 페이지 간 일치 + 전체 핀 수(25) 와 동일 (HIGH-1)
        long firstTotalCount = first.getBody().get("data").get("totalCount").asLong();
        long secondTotalCount = second.getBody().get("data").get("totalCount").asLong();
        assertThat(firstTotalCount).isEqualTo(secondTotalCount);
        assertThat(firstTotalCount).isEqualTo(25L);
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=abc&size=20 - 비숫자 page 는 400 PIN_PAGE_PARAM_INVALID (MEDIUM-1).")
    @Test
    void listPins_nonNumericPage_returns400PinPageParamInvalid() {
        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "page=abc&size=20");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PAGE_PARAM_INVALID");
    }

    @DisplayName("GET /api/v1/groups/{groupId}/pins?page=0&size=xyz - 비숫자 size 는 400 PIN_PAGE_PARAM_INVALID (MEDIUM-1).")
    @Test
    void listPins_nonNumericSize_returns400PinPageParamInvalid() {
        // act
        ResponseEntity<JsonNode> response = listPinsRaw(tokenA, groupId, "page=0&size=xyz");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PIN_PAGE_PARAM_INVALID");
    }
}
