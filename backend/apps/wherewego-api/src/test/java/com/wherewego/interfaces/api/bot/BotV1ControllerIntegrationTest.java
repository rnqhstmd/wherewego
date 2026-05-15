package com.wherewego.interfaces.api.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.bot.BotLinkCode;
import com.wherewego.domain.bot.BotLinkCodeStatus;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.bot.BotLinkCodeJpaRepository;
import com.wherewego.infrastructure.bot.BotUserMappingJpaRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class BotV1ControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BotLinkCodeJpaRepository botLinkCodeJpaRepository;

    @Autowired
    private BotUserMappingJpaRepository botUserMappingJpaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String accessToken;

    @BeforeEach
    void cleanUp() {
        truncateAll();
        UserModel saved = userJpaRepository.save(UserModel.create(1112223334L, "bot-tester", null));
        this.userId = saved.getId();
        this.accessToken = jwtTokenProvider.issueAccessToken(userId);
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        botLinkCodeJpaRepository.deleteAll();
        botUserMappingJpaRepository.deleteAll();
        userJpaRepository.deleteAll();
    }

    private ResponseEntity<JsonNode> issueLinkCode(String accessTokenValue) {
        HttpHeaders headers = new HttpHeaders();
        if (accessTokenValue != null) {
            headers.add(HttpHeaders.COOKIE, "access_token=" + accessTokenValue);
        }
        return restTemplate.exchange(
                "/api/v1/bot/link-codes",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                JsonNode.class
        );
    }

    @DisplayName("POST /api/v1/bot/link-codes - 인증된 사용자에게 6자리 숫자 코드와 expiresAt 을 반환한다 (AC-1).")
    @Test
    void issueLinkCode_authenticated_returns6DigitCode() {
        // act
        ResponseEntity<JsonNode> response = issueLinkCode(accessToken);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().get("data");
        assertThat(data).isNotNull();

        String code = data.get("code").asText();
        assertThat(code).hasSize(6);
        assertThat(code.chars().allMatch(Character::isDigit)).isTrue();
        assertThat(data.get("expiresAt").asText()).isNotBlank();
    }

    @DisplayName("POST /api/v1/bot/link-codes - 기존 ACTIVE 코드가 있어도 신규 코드를 반환하고 이전 코드는 EXPIRED 로 만료된다 (AC-2).")
    @Test
    void issueLinkCode_existingActive_returnsNewCode() {
        // arrange : 1차 발급
        ResponseEntity<JsonNode> first = issueLinkCode(accessToken);
        String firstCode = first.getBody().get("data").get("code").asText();

        // act : 2차 발급
        ResponseEntity<JsonNode> second = issueLinkCode(accessToken);

        // assert
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondCode = second.getBody().get("data").get("code").asText();
        assertThat(secondCode).isNotEqualTo(firstCode);

        // DB : 1차 EXPIRED + 2차 ACTIVE
        List<BotLinkCode> all = botLinkCodeJpaRepository.findAll();
        BotLinkCode firstEntity = all.stream().filter(e -> e.getCode().equals(firstCode)).findFirst().orElseThrow();
        BotLinkCode secondEntity = all.stream().filter(e -> e.getCode().equals(secondCode)).findFirst().orElseThrow();
        assertThat(firstEntity.getStatus()).isEqualTo(BotLinkCodeStatus.EXPIRED);
        assertThat(secondEntity.getStatus()).isEqualTo(BotLinkCodeStatus.ACTIVE);
    }

    @DisplayName("POST /api/v1/bot/link-codes - access_token 쿠키가 없으면 401 을 반환한다.")
    @Test
    void issueLinkCode_unauthenticated_returns401() {
        // act
        ResponseEntity<JsonNode> response = issueLinkCode(null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
