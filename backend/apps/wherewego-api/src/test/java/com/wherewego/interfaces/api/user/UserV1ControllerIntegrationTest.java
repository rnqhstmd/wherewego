package com.wherewego.interfaces.api.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.image.AvatarStorage;
import com.wherewego.domain.user.UserModel;
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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class UserV1ControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // GP-1: 프로필 사진 업로드 시 실제 S3 호출을 피하기 위해 저장 포트를 mock 으로 대체한다.
    @MockBean
    private AvatarStorage avatarStorage;

    private Long userId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        truncateAll();
        UserModel user = userJpaRepository.save(UserModel.create(30000001L, "기존닉", "http://img.example/p.png"));
        this.userId = user.getId();
        this.accessToken = jwtTokenProvider.issueAccessToken(userId);
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

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.add(HttpHeaders.COOKIE, "access_token=" + token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<JsonNode> getCurrentUser(String token) {
        return restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> updateNickname(String token, String nickname) {
        Map<String, String> body = new HashMap<>();
        body.put("nickname", nickname);
        return restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PUT,
                new HttpEntity<>(body, authHeaders(token)),
                JsonNode.class);
    }

    /** 멀티파트 프로필 사진 업로드(JPEG 매직바이트로 시작하는 최소 바이트). */
    private ResponseEntity<JsonNode> uploadProfileImage(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.add(HttpHeaders.COOKIE, "access_token=" + token);
        }
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        Resource file = new ByteArrayResource(jpeg) {
            @Override
            public String getFilename() {
                return "avatar.jpg";
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_JPEG);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, fileHeaders));
        return restTemplate.exchange(
                "/api/v1/users/me/profile-image",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> deleteProfileImage(String token) {
        return restTemplate.exchange(
                "/api/v1/users/me/profile-image",
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)),
                JsonNode.class);
    }

    @DisplayName("GET /api/v1/users/me - access_token 쿠키가 없으면 401 을 반환한다.")
    @Test
    void getCurrentUser_unauthenticated_returns401() {
        // act
        ResponseEntity<JsonNode> response = getCurrentUser(null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @DisplayName("GET /api/v1/users/me - 인증된 사용자면 200 과 프로필 정보를 반환한다. "
            + "GP-1: 업로드 프사 키가 없으면 profileImageUrl 은 카카오 URL 로 폴백한다.")
    @Test
    void getCurrentUser_authenticated_returns200WithProfile() {
        // act
        ResponseEntity<JsonNode> response = getCurrentUser(accessToken);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");

        JsonNode data = body.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("id").asLong()).isEqualTo(userId);
        assertThat(data.get("nickname").asText()).isEqualTo("기존닉");
        // 업로드 키 없음 → 카카오 URL 폴백(유효 프사 URL 규칙).
        assertThat(data.get("profileImageUrl").asText()).isEqualTo("http://img.example/p.png");
        // IG-2 FR-5: 핀이 없으면 pinCount 는 0.
        assertThat(data.get("pinCount").asLong()).isEqualTo(0);
    }

    @DisplayName("GET /api/v1/users/me - IG-2 FR-5: pinCount 는 내가 등록한 활성 핀(soft-delete 제외) 전 그룹 합산이다.")
    @Test
    void getCurrentUser_pinCount_countsMyActivePinsAcrossGroups() {
        // arrange : 내가 등록한 활성 핀 2개 + soft-delete 핀 1개 + 타인 핀 1개.
        Long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id", Long.class, "여행팀");
        jdbcTemplate.update("INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", groupId, userId);
        UserModel other = userJpaRepository.save(UserModel.create(30000009L, "타인", null));

        insertPin(groupId, userId, "성수", false);
        insertPin(groupId, userId, "한남", false);
        insertPin(groupId, userId, "삭제됨", true);          // soft-delete → 제외
        insertPin(groupId, other.getId(), "타인장소", false);  // 타인 핀 → 제외

        // act
        ResponseEntity<JsonNode> response = getCurrentUser(accessToken);

        // assert : 활성 + 본인 등록만 합산 → 2.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("data").get("pinCount").asLong()).isEqualTo(2);
    }

    /** 핀 직접 INSERT (V001 스키마). {@code deleted} 면 deleted_at 을 채워 soft-delete 핀으로 만든다. */
    private void insertPin(Long groupId, Long createdBy, String placeName, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO pins (group_id, created_by, place_name, address, latitude, longitude, "
                        + "instagram_url, memo, memo_source, tag, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                groupId, createdBy, placeName, "서울 강남구",
                new java.math.BigDecimal("37.5000000"), new java.math.BigDecimal("127.0000000"),
                null, null, null, "MEMORY", deleted ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    @DisplayName("PUT /api/v1/users/me - access_token 쿠키가 없으면 401 을 반환한다.")
    @Test
    void updateNickname_unauthenticated_returns401() {
        // act
        ResponseEntity<JsonNode> response = updateNickname(null, "새닉네임");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @DisplayName("PUT /api/v1/users/me - 인증된 사용자가 정상 닉네임으로 변경하면 200 과 업데이트된 정보를 반환한다.")
    @Test
    void updateNickname_validNickname_returns200WithUpdatedProfile() {
        // act
        ResponseEntity<JsonNode> response = updateNickname(accessToken, "새닉네임");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("SUCCESS");

        JsonNode data = body.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("id").asLong()).isEqualTo(userId);
        assertThat(data.get("nickname").asText()).isEqualTo("새닉네임");

        // DB : 닉네임 갱신 + profileImageUrl 보존
        UserModel updated = userJpaRepository.findById(userId).orElseThrow();
        assertThat(updated.getNickname()).isEqualTo("새닉네임");
        assertThat(updated.getProfileImageUrl()).isEqualTo("http://img.example/p.png");
    }

    @DisplayName("PUT /api/v1/users/me - 닉네임이 1자면 400 을 반환한다.")
    @Test
    void updateNickname_tooShort_returns400() {
        // act
        ResponseEntity<JsonNode> response = updateNickname(accessToken, "가");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("FAIL");
    }

    @DisplayName("PUT /api/v1/users/me - 닉네임이 6자면 400 을 반환한다.")
    @Test
    void updateNickname_tooLong_returns400() {
        // arrange
        String tooLong = "a".repeat(6);

        // act
        ResponseEntity<JsonNode> response = updateNickname(accessToken, tooLong);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("FAIL");
    }

    @DisplayName("PUT /api/v1/users/me - 닉네임에 특수문자가 포함되면 400 을 반환한다.")
    @Test
    void updateNickname_specialChars_returns400() {
        // act
        ResponseEntity<JsonNode> response = updateNickname(accessToken, "닉네임!@#");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("meta").get("result").asText()).isEqualTo("FAIL");
    }

    @DisplayName("GP-1 FR-3: POST /api/v1/users/me/profile-image - 업로드하면 200 과 profileImageUrl=썸네일 키 공개 URL(유효 URL 1순위), DB 키 갱신.")
    @Test
    void uploadProfileImage_storesAndReturnsThumbUrl() {
        // arrange : 저장 포트 mock — 업로드 키 쌍을 반환.
        when(avatarStorage.store(eq("users/" + userId + "/avatar"), any(byte[].class), anyString()))
                .thenReturn(new AvatarStorage.StoredAvatar(
                        "users/" + userId + "/avatar/u.jpg", "users/" + userId + "/avatar/u_thumb.webp"));

        // act
        ResponseEntity<JsonNode> response = uploadProfileImage(accessToken);

        // assert : 유효 URL 1순위 = 썸네일 키 공개 URL(카카오 URL 보다 우선) + DB 키 갱신.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("profileImageUrl").asText()).endsWith("/users/" + userId + "/avatar/u_thumb.webp");
        UserModel updated = userJpaRepository.findById(userId).orElseThrow();
        assertThat(updated.getProfileImageKey()).isEqualTo("users/" + userId + "/avatar/u.jpg");
        assertThat(updated.getProfileImageThumbKey()).isEqualTo("users/" + userId + "/avatar/u_thumb.webp");
    }

    @DisplayName("GP-1 FR-3/AC-4: DELETE /api/v1/users/me/profile-image - 제거하면 200, profileImageUrl=null, 업로드 키와 카카오 URL 까지 비움 + S3 회수.")
    @Test
    void deleteProfileImage_clearsAllAndReturnsNull() {
        // arrange : 먼저 업로드해 키를 채운다(카카오 URL 도 보유 상태).
        when(avatarStorage.store(anyString(), any(byte[].class), anyString()))
                .thenReturn(new AvatarStorage.StoredAvatar("users/x/u.jpg", "users/x/u_thumb.webp"));
        uploadProfileImage(accessToken);

        // act
        ResponseEntity<JsonNode> response = deleteProfileImage(accessToken);

        // assert : null 응답(키>카카오>null 전부 제거) + DB 전 필드 null + S3 회수.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("data").get("profileImageUrl").isNull()).isTrue();
        UserModel cleared = userJpaRepository.findById(userId).orElseThrow();
        assertThat(cleared.getProfileImageKey()).isNull();
        assertThat(cleared.getProfileImageThumbKey()).isNull();
        assertThat(cleared.getProfileImageUrl()).isNull(); // 카카오 URL 까지 비움(동기화 중단으로 복원 없음).
        verify(avatarStorage).deleteQuietly("users/x/u.jpg", "users/x/u_thumb.webp");
    }

    @DisplayName("GP-1: 프사 키도 카카오 URL 도 없으면(제거 후) profileImageUrl 은 null 폴백(키>카카오>null).")
    @Test
    void getCurrentUser_noKeyNoKakao_returnsNull() {
        // arrange : 카카오 URL 없는 사용자로 교체.
        truncateAll();
        UserModel noUrl = userJpaRepository.save(UserModel.create(30000002L, "노프사", null));
        String token = jwtTokenProvider.issueAccessToken(noUrl.getId());

        // act
        ResponseEntity<JsonNode> response = getCurrentUser(token);

        // assert : 키 없음 + 카카오 URL 없음 → null.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("data").get("profileImageUrl").isNull()).isTrue();
    }
}
