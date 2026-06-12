package com.wherewego.interfaces.api.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.group.GroupMember;
import com.wherewego.domain.image.AvatarStorage;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // GP-1: 그룹 이미지 업로드 시 실제 S3 호출을 피하기 위해 저장 포트를 mock 으로 대체한다.
    @MockBean
    private AvatarStorage avatarStorage;

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
        // Phase 10 V009: notifications.visit_pin_id → pins(id) FK 추가로 인해 pins 보다 먼저 삭제 필요.
        jdbcTemplate.execute("DELETE FROM notification_pins");
        jdbcTemplate.execute("DELETE FROM notifications");
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

    private ResponseEntity<JsonNode> previewBySlug(String slug) {
        // 공개 엔드포인트 — 인증 헤더 없이 호출한다.
        return restTemplate.exchange(
                "/api/v1/groups/invite-links/by-slug/" + slug,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(null)),
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

    private ResponseEntity<JsonNode> getMyGroups(String accessToken) {
        return restTemplate.exchange(
                "/api/v1/groups",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> listMembers(String accessToken, Long groupId) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/members",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> renameGroup(String accessToken, Long groupId, String name) {
        String body = "{\"name\":\"" + (name == null ? "" : name.replace("\"", "\\\"")) + "\"}";
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(accessToken)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> deleteGroup(String accessToken, Long groupId) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    /** 멀티파트 그룹 이미지 업로드(JPEG 매직바이트로 시작하는 최소 바이트). */
    private ResponseEntity<JsonNode> uploadGroupImage(String accessToken, Long groupId) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.add(HttpHeaders.COOKIE, "access_token=" + accessToken);
        }
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // JPEG 매직(FF D8 FF) 으로 시작해야 ImageUploadGuard 의 매직바이트 게이트를 통과한다.
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        Resource file = new ByteArrayResource(jpeg) {
            @Override
            public String getFilename() {
                return "avatar.jpg";
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_JPEG);
        HttpEntity<Resource> filePart = new HttpEntity<>(file, fileHeaders);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/image",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> deleteGroupImage(String accessToken, Long groupId) {
        return restTemplate.exchange(
                "/api/v1/groups/" + groupId + "/image",
                HttpMethod.DELETE,
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

    @DisplayName("IC-1(AC-7): POST /api/v1/groups/invite-links/{token}/accept - 응답이 {groupId, acceptedAt} 구조를 유지한다 (BC 회귀가드).")
    @Test
    void acceptInviteLink_responseStructure_unchanged() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();

        // act
        ResponseEntity<JsonNode> response = acceptInviteLink(tokenB, inviteToken);

        // assert : 200 + data 에 groupId/acceptedAt 만 존재(IC-1 내부 재설계 후에도 계약 불변)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.has("groupId")).isTrue();
        assertThat(data.has("acceptedAt")).isTrue();
        assertThat(data.get("groupId").asLong()).isEqualTo(groupId);
        assertThat(data.get("acceptedAt").asText()).isNotBlank();
    }

    @DisplayName("IC-1(AC-6)+GP-1 FR-8: GET /api/v1/groups/invite-links/by-slug/{slug} - 유효 코드이지만 정원(8) 도달이면 409 GROUP_CAPACITY_EXCEEDED 를 반환한다.")
    @Test
    void previewBySlug_capacityReached_returns409() {
        // arrange : userA 생성(1명) + 코드 발급. 동일 코드를 재사용해 정원 8 을 채운다(정원 10→8 축소).
        ResponseEntity<JsonNode> created = createGroup(tokenA, "정원그룹");
        Long groupId = created.getBody().get("data").get("groupId").asLong();
        JsonNode inviteData = issueInviteLink(tokenA, groupId).getBody().get("data");
        String token = inviteData.get("token").asText();
        String slug = inviteData.get("slug").asText();

        // 서로 다른 7명을 동일 코드로 가입시켜 정원 8 도달
        for (int i = 0; i < 7; i++) {
            UserModel member = userJpaRepository.save(
                    UserModel.create(20000100L + i, "member" + i, null));
            String memberToken = jwtTokenProvider.issueAccessToken(member.getId());
            acceptInviteLink(memberToken, token);
        }
        assertThat(groupMemberJpaRepository.countActiveByGroupId(groupId)).isEqualTo(8L);

        // act : 정원 도달 후 by-slug 미리보기
        ResponseEntity<JsonNode> response = previewBySlug(slug);

        // assert : 만료(404)가 아니라 정원초과(409 GROUP_CAPACITY_EXCEEDED) 구분 응답
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("errorCode").asText()).isEqualTo("GROUP_CAPACITY_EXCEEDED");
    }

    @DisplayName("IC-1: GET /api/v1/groups/invite-links/by-slug/{slug} - 유효 코드(정원 미도달)이면 200 과 그룹명/초대자/만료시각을 반환한다.")
    @Test
    void previewBySlug_valid_returns200() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String slug = issueInviteLink(tokenA, groupId).getBody().get("data").get("slug").asText();

        // act
        ResponseEntity<JsonNode> response = previewBySlug(slug);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("groupName").asText()).isEqualTo("여행팀");
        assertThat(data.get("inviterNickname").asText()).isNotBlank();
        assertThat(data.get("expiresAt").asText()).isNotBlank();
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

    @DisplayName("GM-1(AC-5): GET /api/v1/groups - 활성 그룹이 2개면 200 과 가입 순 배열(길이 2)을 반환한다.")
    @Test
    void getMyGroups_multipleGroups_returnsArray() {
        // arrange : userA 가 두 그룹을 순서대로 생성 (joined_at ASC = 생성 순)
        Long firstGroupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        Long secondGroupId = createGroup(tokenA, "맛집팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = getMyGroups(tokenA);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");

        JsonNode data = body.get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(2);

        // 가입 순(joined_at ASC): 첫 그룹 → 두 번째 그룹
        JsonNode firstItem = data.get(0);
        assertThat(firstItem.get("groupId").asLong()).isEqualTo(firstGroupId);
        assertThat(firstItem.get("name").asText()).isEqualTo("여행팀");
        assertThat(firstItem.get("createdAt").asText()).isNotBlank();
        assertThat(firstItem.get("memberCount").asLong()).isEqualTo(1L);

        JsonNode secondItem = data.get(1);
        assertThat(secondItem.get("groupId").asLong()).isEqualTo(secondGroupId);
        assertThat(secondItem.get("name").asText()).isEqualTo("맛집팀");
        assertThat(secondItem.get("memberCount").asLong()).isEqualTo(1L);
    }

    @DisplayName("GM-1(AC-6): GET /api/v1/groups - 활성 그룹이 없으면 200 과 빈 배열을 반환한다.")
    @Test
    void getMyGroups_noGroups_returnsEmptyArray() {
        // act : userB 는 그룹 없음
        ResponseEntity<JsonNode> response = getMyGroups(tokenB);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");
        JsonNode data = body.get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).isEmpty();
    }

    @DisplayName("GM-1: GET /api/v1/groups - access_token 쿠키가 없으면 401 을 반환한다.")
    @Test
    void getMyGroups_unauthenticated_returns401() {
        // act
        ResponseEntity<JsonNode> response = getMyGroups(null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @DisplayName("GM-2: GET /api/v1/groups/{groupId}/members - 활성 멤버가 조회하면 200 과 가입 순 목록을 반환하고 첫 항목만 isOwner=true 다.")
    @Test
    void listMembers_member_returnsListWithOwnerMarked() {
        // arrange : userA(방장) 그룹 + userB 수락
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();
        acceptInviteLink(tokenB, inviteToken);

        // act
        ResponseEntity<JsonNode> response = listMembers(tokenA, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(2);
        // 가입 순(joined_at ASC): 방장 userA 가 첫 항목.
        assertThat(data.get(0).get("userId").asLong()).isEqualTo(userAId);
        assertThat(data.get(0).get("nickname").asText()).isEqualTo("userA");
        assertThat(data.get(0).get("isOwner").asBoolean()).isTrue();
        assertThat(data.get(1).get("userId").asLong()).isEqualTo(userBId);
        assertThat(data.get(1).get("isOwner").asBoolean()).isFalse();
    }

    @DisplayName("GM-2: GET /api/v1/groups/{groupId}/members - 비멤버가 조회하면 403 GROUP_NOT_MEMBER 를 반환한다.")
    @Test
    void listMembers_notMember_returns403() {
        // arrange : userA 그룹, userB 비멤버
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = listMembers(tokenB, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("GROUP_NOT_MEMBER");
    }

    @DisplayName("GM-2: PATCH /api/v1/groups/{groupId} - 활성 멤버가 이름을 수정하면 200 과 DB 의 그룹명이 갱신된다.")
    @Test
    void renameGroup_member_returns200AndUpdatesName() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = renameGroup(tokenA, groupId, "맛집투어팀");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("meta").get("result").asText()).isEqualTo("SUCCESS");
        // DB : 그룹명 갱신 확인
        assertThat(groupJpaRepository.findById(groupId).orElseThrow().getName()).isEqualTo("맛집투어팀");
    }

    @DisplayName("GM-2: PATCH /api/v1/groups/{groupId} - 빈 이름이면 400 GROUP_NAME_INVALID 를 반환한다.")
    @Test
    void renameGroup_blankName_returns400() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = renameGroup(tokenA, groupId, "   ");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("GROUP_NAME_INVALID");
    }

    @DisplayName("GM-2: PATCH /api/v1/groups/{groupId} - 비멤버가 수정하면 403 GROUP_NOT_MEMBER 를 반환한다.")
    @Test
    void renameGroup_notMember_returns403() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = renameGroup(tokenB, groupId, "새이름");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("GROUP_NOT_MEMBER");
    }

    @DisplayName("GM-2: DELETE /api/v1/groups/{groupId} - 방장이 삭제하면 200 과 전원 탈퇴 + 그룹 soft delete 가 적용된다.")
    @Test
    void deleteGroup_owner_returns200AndSoftDeletes() {
        // arrange : userA(방장) 그룹 + userB 수락
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();
        acceptInviteLink(tokenB, inviteToken);

        // act : 방장 userA 가 삭제
        ResponseEntity<JsonNode> response = deleteGroup(tokenA, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("meta").get("result").asText()).isEqualTo("SUCCESS");
        // DB : 활성 멤버 0명 + 그룹 soft delete
        assertThat(groupMemberJpaRepository.countActiveByGroupId(groupId)).isEqualTo(0L);
        assertThat(groupJpaRepository.findById(groupId).orElseThrow().getDeletedAt()).isNotNull();
    }

    @DisplayName("GM-2: DELETE /api/v1/groups/{groupId} - 방장이 아닌 멤버가 삭제하면 403 GROUP_OWNER_REQUIRED 를 반환하고 그룹은 유지된다.")
    @Test
    void deleteGroup_notOwner_returns403() {
        // arrange : userA(방장) 그룹 + userB 수락(비방장)
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();
        acceptInviteLink(tokenB, inviteToken);

        // act : 비방장 userB 가 삭제 시도
        ResponseEntity<JsonNode> response = deleteGroup(tokenB, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("GROUP_OWNER_REQUIRED");
        // 그룹은 그대로 활성
        assertThat(groupMemberJpaRepository.countActiveByGroupId(groupId)).isEqualTo(2L);
        assertThat(groupJpaRepository.findById(groupId).orElseThrow().getDeletedAt()).isNull();
    }

    @DisplayName("GM-2: DELETE /api/v1/groups/{groupId} - 방장 탈퇴 후 자동 승계된 방장(다음 최선임)이 삭제할 수 있다.")
    @Test
    void deleteGroup_ownerSuccession_nextSeniorCanDelete() {
        // arrange : userA(방장) 그룹 + userB 수락 → userA 탈퇴 → userB 가 방장 승계
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();
        acceptInviteLink(tokenB, inviteToken);
        leaveGroup(tokenA, groupId);

        // act : 승계된 방장 userB 가 삭제
        ResponseEntity<JsonNode> response = deleteGroup(tokenB, groupId);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(groupMemberJpaRepository.countActiveByGroupId(groupId)).isEqualTo(0L);
        assertThat(groupJpaRepository.findById(groupId).orElseThrow().getDeletedAt()).isNotNull();
    }

    @DisplayName("GM-1(AC-8): GET /api/v1/groups/me - 활성 그룹이 2개여도 최신(id DESC) 1개만 반환한다 (BR-4 웹 호환 가드).")
    @Test
    void getMyActiveGroup_multipleGroups_returnsLatest() {
        // arrange : userA 가 두 그룹을 순서대로 생성 (두 번째 그룹이 최신 = 더 큰 id)
        createGroup(tokenA, "여행팀");
        Long secondGroupId = createGroup(tokenA, "맛집팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = getMyActiveGroup(tokenA);

        // assert : /me 는 최신 그룹(두 번째) 1개만 반환
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("groupId").asLong()).isEqualTo(secondGroupId);
        assertThat(data.get("name").asText()).isEqualTo("맛집팀");
    }

    @DisplayName("GP-1 FR-4: GET /api/v1/groups - 각 그룹에 imageUrl/imageThumbUrl(미지정 null)과 멤버 프리뷰(가입순)를 포함한다.")
    @Test
    void getMyGroups_includesImageAndMemberPreviews() {
        // arrange : userA 그룹 + userB 합류(가입순 A→B). 이미지는 미지정.
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        String inviteToken = issueInviteLink(tokenA, groupId).getBody().get("data").get("token").asText();
        acceptInviteLink(tokenB, inviteToken);

        // act
        ResponseEntity<JsonNode> response = getMyGroups(tokenA);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = response.getBody().get("data").get(0);
        // 이미지 미지정 → null(클라가 콜라주 렌더).
        assertThat(item.get("imageUrl").isNull()).isTrue();
        assertThat(item.get("imageThumbUrl").isNull()).isTrue();
        // 멤버 프리뷰 가입순(A→B), 프사 없으면 profileImageUrl null.
        JsonNode members = item.get("members");
        assertThat(members.isArray()).isTrue();
        assertThat(members).hasSize(2);
        assertThat(members.get(0).get("userId").asLong()).isEqualTo(userAId);
        assertThat(members.get(0).get("nickname").asText()).isEqualTo("userA");
        assertThat(members.get(0).get("profileImageUrl").isNull()).isTrue();
        assertThat(members.get(1).get("userId").asLong()).isEqualTo(userBId);
    }

    @DisplayName("GP-1 FR-9: GET /api/v1/groups/{groupId}/members - 각 멤버에 profileImageUrl(없으면 null) 필드가 포함된다.")
    @Test
    void listMembers_includesProfileImageUrl() {
        // arrange
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = listMembers(tokenA, groupId);

        // assert : 프사 없는 사용자는 profileImageUrl null 키 존재.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode member = response.getBody().get("data").get(0);
        assertThat(member.has("profileImageUrl")).isTrue();
        assertThat(member.get("profileImageUrl").isNull()).isTrue();
    }

    @DisplayName("GP-1 FR-1/FR-2: POST /api/v1/groups/{groupId}/image - 활성 멤버가 업로드하면 200 과 공개 URL + DB 키가 갱신된다.")
    @Test
    void uploadGroupImage_member_updatesKeyAndReturnsUrls() {
        // arrange : userA 그룹 + 저장 포트 mock.
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        when(avatarStorage.store(eq("groups/" + groupId + "/avatar"), any(byte[].class), anyString()))
                .thenReturn(new AvatarStorage.StoredAvatar(
                        "groups/" + groupId + "/avatar/u.jpg", "groups/" + groupId + "/avatar/u_thumb.webp"));

        // act
        ResponseEntity<JsonNode> response = uploadGroupImage(tokenA, groupId);

        // assert : 200 + 공개 URL 응답 + DB image_key 갱신.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("imageUrl").asText()).endsWith("/groups/" + groupId + "/avatar/u.jpg");
        assertThat(data.get("imageThumbUrl").asText()).endsWith("_thumb.webp");
        assertThat(groupJpaRepository.findById(groupId).orElseThrow().getImageKey())
                .isEqualTo("groups/" + groupId + "/avatar/u.jpg");
    }

    @DisplayName("GP-1(AC-2): POST /api/v1/groups/{groupId}/image - 비멤버가 업로드하면 403 GROUP_NOT_MEMBER 이고 저장은 호출되지 않는다.")
    @Test
    void uploadGroupImage_notMember_returns403() {
        // arrange : userA 그룹, userB 비멤버.
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();

        // act
        ResponseEntity<JsonNode> response = uploadGroupImage(tokenB, groupId);

        // assert : 403 + 저장 미호출(권한 검증이 store 앞).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("meta").get("errorCode").asText()).isEqualTo("GROUP_NOT_MEMBER");
    }

    @DisplayName("GP-1 FR-2: 업로드 후 재업로드(교체) 시 이전 키가 best-effort 회수되고 새 키로 갱신된다.")
    @Test
    void uploadGroupImage_replace_recoversOldKeys() {
        // arrange : 첫 업로드로 키를 채운다.
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        when(avatarStorage.store(anyString(), any(byte[].class), anyString()))
                .thenReturn(new AvatarStorage.StoredAvatar("groups/x/old.jpg", "groups/x/old_thumb.webp"))
                .thenReturn(new AvatarStorage.StoredAvatar("groups/x/new.jpg", "groups/x/new_thumb.webp"));
        uploadGroupImage(tokenA, groupId);

        // act : 교체 업로드.
        ResponseEntity<JsonNode> response = uploadGroupImage(tokenA, groupId);

        // assert : 새 키로 갱신 + 이전 키 회수(deleteQuietly).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(groupJpaRepository.findById(groupId).orElseThrow().getImageKey()).isEqualTo("groups/x/new.jpg");
        verify(avatarStorage).deleteQuietly("groups/x/old.jpg", "groups/x/old_thumb.webp");
    }

    @DisplayName("GP-1 FR-2: DELETE /api/v1/groups/{groupId}/image - 활성 멤버가 제거하면 200, null URL, DB 키 비움 + S3 회수.")
    @Test
    void deleteGroupImage_member_clearsKeyAndReturnsNulls() {
        // arrange : 업로드로 키를 채운 뒤 제거.
        Long groupId = createGroup(tokenA, "여행팀").getBody().get("data").get("groupId").asLong();
        when(avatarStorage.store(anyString(), any(byte[].class), anyString()))
                .thenReturn(new AvatarStorage.StoredAvatar("groups/x/u.jpg", "groups/x/u_thumb.webp"));
        uploadGroupImage(tokenA, groupId);

        // act
        ResponseEntity<JsonNode> response = deleteGroupImage(tokenA, groupId);

        // assert : 200 + null URL + DB 키 비움 + 회수.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("imageUrl").isNull()).isTrue();
        assertThat(data.get("imageThumbUrl").isNull()).isTrue();
        assertThat(groupJpaRepository.findById(groupId).orElseThrow().getImageKey()).isNull();
        verify(avatarStorage).deleteQuietly("groups/x/u.jpg", "groups/x/u_thumb.webp");
    }
}
