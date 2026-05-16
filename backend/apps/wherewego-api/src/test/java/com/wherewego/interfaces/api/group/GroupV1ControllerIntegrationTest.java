package com.wherewego.interfaces.api.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.group.GroupMember;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.group.GroupJpaRepository;
import com.wherewego.infrastructure.group.GroupMemberJpaRepository;
import com.wherewego.infrastructure.group.InviteLinkJpaRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class GroupV1ControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private GroupJpaRepository groupJpaRepository;

    @Autowired
    private InviteLinkJpaRepository inviteLinkJpaRepository;

    @Autowired
    private GroupMemberJpaRepository groupMemberJpaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userAId;
    private Long userBId;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void cleanUp() {
        truncateAll();
        UserModel userA = userJpaRepository.save(UserModel.create(20000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(20000002L, "userB", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.tokenA = jwtTokenProvider.issueAccessToken(userAId);
        this.tokenB = jwtTokenProvider.issueAccessToken(userBId);
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
        String body = "{\"name\":\"" + (name == null ? "" : name.replace("\"", "\\\"")) + "\"}";
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

    private ResponseEntity<JsonNode> leaveGroup(String accessToken, Long groupId) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/members/me",
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> getMyActiveGroup(String accessToken) {
        return restTemplate.exchange(
                "/api/v1/groups/me",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    @DisplayName("POST /api/v1/groups - 인증된 사용자의 그룹 생성 요청에 201 과 groupId/name 을 반환한다.")
    @Test
    void createGroup_authenticated_returns201WithGroupId() {
        // act
        ResponseEntity<JsonNode> response = createGroup(tokenA, "여행팀");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");

        JsonNode data = body.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("groupId").asLong()).isPositive();
        assertThat(data.get("name").asText()).isEqualTo("여행팀");
        assertThat(data.get("createdAt").asText()).isNotBlank();
    }

    @DisplayName("POST /api/v1/groups - access_token 쿠키가 없으면 401 을 반환한다.")
    @Test
    void createGroup_unauthenticated_returns401() {
        // act
        ResponseEntity<JsonNode> response = createGroup(null, "여행팀");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @DisplayName("POST /api/v1/groups - 이름이 공백이면 400 GROUP_NAME_INVALID 를 반환한다 (AC-2).")
    @Test
    void createGroup_blankName_returns400() {
        // act
        ResponseEntity<JsonNode> response = createGroup(tokenA, "");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("FAIL");
        // 서비스 레이어에서 이름 검증 → ErrorType.GROUP_NAME_INVALID
        assertThat(body.get("meta").get("errorCode").asText()).isEqualTo("GROUP_NAME_INVALID");
    }

    @DisplayName("POST /api/v1/groups - 이름이 31 자 이상이면 400 GROUP_NAME_INVALID 를 반환한다 (AC-3).")
    @Test
    void createGroup_tooLongName_returns400() {
        // arrange
        String tooLong = "a".repeat(31);

        // act
        ResponseEntity<JsonNode> response = createGroup(tokenA, tooLong);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("FAIL");
        // 서비스 레이어에서 이름 검증 → ErrorType.GROUP_NAME_INVALID
        assertThat(body.get("meta").get("errorCode").asText()).isEqualTo("GROUP_NAME_INVALID");
    }

    @DisplayName("POST /api/v1/groups/{groupId}/invite-links - 활성 멤버가 발급하면 201 과 token, expiresAt 을 반환한다.")
    @Test
    void issueInviteLink_member_returns201WithToken() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = issueInviteLink(tokenA, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("token").asText()).isNotBlank();
        assertThat(data.get("expiresAt").asText()).isNotBlank();
    }

    @DisplayName("POST /api/v1/groups/{groupId}/invite-links - 비멤버가 발급 시도하면 403 GROUP_NOT_MEMBER 를 반환한다.")
    @Test
    void issueInviteLink_notMember_returns403() {
        // arrange : userA 가 그룹 생성, userB 는 비멤버
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = issueInviteLink(tokenB, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("errorCode").asText()).isEqualTo("GROUP_NOT_MEMBER");
    }

    @DisplayName("POST /api/v1/groups/invite-links/{token}/accept - 유효 토큰을 수락하면 200 과 활성 멤버 2 명이 된다.")
    @Test
    void acceptInviteLink_valid_returns200() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();

        // act
        ResponseEntity<JsonNode> response = acceptInviteLink(tokenB, inviteToken);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("groupId").asLong()).isEqualTo(groupId);
        assertThat(data.get("acceptedAt").asText()).isNotBlank();

        // DB : 활성 멤버 2 명
        long activeCount = groupMemberJpaRepository.countActiveByGroupId(groupId);
        assertThat(activeCount).isEqualTo(2L);
    }

    @DisplayName("POST /api/v1/groups/invite-links/{token}/accept - 본인이 발급한 토큰을 본인이 수락하면 400 INVITE_LINK_SELF_ACCEPT 를 반환한다 (AC-15).")
    @Test
    void acceptInviteLink_selfAccept_returns400() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();

        // act : 발급자 userA 본인이 수락
        ResponseEntity<JsonNode> response = acceptInviteLink(tokenA, inviteToken);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("errorCode").asText()).isEqualTo("INVITE_LINK_SELF_ACCEPT");
    }

    @DisplayName("DELETE /api/v1/groups/{groupId}/members/me - 활성 멤버가 탈퇴하면 200 과 leftAt 이 기록된다 (AC-11).")
    @Test
    void leaveGroup_member_returns200() {
        // arrange : userA 그룹 + userB 수락
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();
        acceptInviteLink(tokenB, inviteToken);

        // act : userA 탈퇴
        ResponseEntity<JsonNode> response = leaveGroup(tokenA, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");

        // DB : userA 의 leftAt 기록 + userB 만 활성
        List<GroupMember> all = groupMemberJpaRepository.findAll();
        GroupMember userAMember = all.stream()
                .filter(m -> m.getUserId().equals(userAId) && m.getGroupId().equals(groupId))
                .findFirst()
                .orElseThrow();
        assertThat(userAMember.getLeftAt()).isNotNull();
        assertThat(groupMemberJpaRepository.countActiveByGroupId(groupId)).isEqualTo(1L);
    }

    @DisplayName("GET /api/v1/groups/me - 활성 그룹이 있으면 200 과 groupId/name 을 반환한다.")
    @Test
    void getMyActiveGroup_active_returnsGroup() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = getMyActiveGroup(tokenA);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("groupId").asLong()).isEqualTo(groupId);
        assertThat(data.get("name").asText()).isEqualTo("여행팀");
        assertThat(data.get("createdAt").asText()).isNotBlank();
    }

    @DisplayName("GET /api/v1/groups/me - 활성 그룹이 없으면 200 과 data=null 을 반환한다.")
    @Test
    void getMyActiveGroup_noActive_returnsNullData() {
        // act : userB 는 그룹 없음
        ResponseEntity<JsonNode> response = getMyActiveGroup(tokenB);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");
        // ObjectMapper 설정에 따라 data 필드가 누락(null Java reference) 또는 NullNode일 수 있다.
        JsonNode dataNode = body.get("data");
        assertThat(dataNode == null || dataNode.isNull()).isTrue();
    }
}
