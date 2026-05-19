package com.wherewego.interfaces.api.user;

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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    @DisplayName("GET /api/v1/users/me - access_token 쿠키가 없으면 401 을 반환한다.")
    @Test
    void getCurrentUser_unauthenticated_returns401() {
        // act
        ResponseEntity<JsonNode> response = getCurrentUser(null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @DisplayName("GET /api/v1/users/me - 인증된 사용자면 200 과 프로필 정보를 반환한다.")
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
        assertThat(data.get("profileImageUrl").asText()).isEqualTo("http://img.example/p.png");
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

    @DisplayName("PUT /api/v1/users/me - 닉네임이 13자면 400 을 반환한다.")
    @Test
    void updateNickname_tooLong_returns400() {
        // arrange
        String tooLong = "a".repeat(13);

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
}
