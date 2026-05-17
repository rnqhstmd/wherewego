package com.wherewego.interfaces.api.place;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.place.google.GooglePlacesClient;
import com.wherewego.infrastructure.place.kakao.KakaoLocalClient;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/v1/places/search} (Phase 6 FR-API-2) 통합 테스트.
 * <p>{@link KakaoLocalClient} 와 {@link GooglePlacesClient} 를 {@link MockitoBean} 으로 대체하여
 * 카카오/Google 분기 시나리오를 결정적으로 재현한다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PlaceSearchIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KakaoLocalClient kakaoLocalClient;

    @MockitoBean
    private GooglePlacesClient googlePlacesClient;

    private String tokenA;

    @BeforeEach
    void cleanUp() {
        truncateAll();

        UserModel userA = userJpaRepository.save(UserModel.create(70000001L, "userA", null));
        this.tokenA = jwtTokenProvider.issueAccessToken(userA.getId());
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

    private ResponseEntity<JsonNode> search(String accessToken, String keyword) {
        String path = "/api/v1/places/search?q=" + keyword;
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                JsonNode.class);
    }

    @DisplayName("GET /api/v1/places/search - 카카오가 결과를 반환하면 200 과 items 배열을 받는다 (Google 폴백 미호출).")
    @Test
    void search_success_returnsKakaoItems() {
        // arrange
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of(
                        new PlaceSearchHit("k1", "성수동 카페", "서울 성동구", 37.5443, 127.0557),
                        new PlaceSearchHit("k2", "성수동 베이커리", "서울 성동구", 37.5444, 127.0558)
                ));

        // act
        ResponseEntity<JsonNode> response = search(tokenA, "성수동");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = response.getBody().get("data").get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("placeName").asText()).isEqualTo("성수동 카페");
    }

    @DisplayName("GET /api/v1/places/search - 빈 q 는 400 PLACE_SEARCH_KEYWORD_INVALID 를 반환한다.")
    @Test
    void search_emptyKeyword_returns400() {
        // act
        ResponseEntity<JsonNode> response = search(tokenA, "");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("meta").get("errorCode").asText())
                .isEqualTo("PLACE_SEARCH_KEYWORD_INVALID");
    }

    @DisplayName("GET /api/v1/places/search - 카카오가 0건이면 Google 폴백 결과를 200 으로 반환한다.")
    @Test
    void search_kakaoEmpty_googleFallback_returnsItems() {
        // arrange
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of());
        when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of(
                        new PlaceSearchHit("g1", "Tokyo Ramen", "Tokyo", 35.6895, 139.6917)
                ));

        // act
        ResponseEntity<JsonNode> response = search(tokenA, "도쿄라멘");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = response.getBody().get("data").get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("placeName").asText()).isEqualTo("Tokyo Ramen");
    }

    @DisplayName("GET /api/v1/places/search - 카카오와 Google 모두 실패해도 200 + 빈 배열을 반환한다 (Q4).")
    @Test
    void search_kakaoFail_googleFail_emptyOk() {
        // arrange
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenThrow(new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED));
        when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenThrow(new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED));

        // act
        ResponseEntity<JsonNode> response = search(tokenA, "어디든");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = response.getBody().get("data").get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).isEmpty();
    }

    @DisplayName("GET /api/v1/places/search - 카카오 실패 시 Google 폴백으로 결과를 받을 수 있다.")
    @Test
    void search_kakaoTimeout_googleFallback_returnsItems() {
        // arrange : 카카오 호출이 CoreException (타임아웃 시뮬레이션)
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenThrow(new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED));
        when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of(
                        new PlaceSearchHit("g1", "Fallback Place", "Somewhere", 37.5, 127.0)
                ));

        // act
        ResponseEntity<JsonNode> response = search(tokenA, "어떤장소");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = response.getBody().get("data").get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("placeName").asText()).isEqualTo("Fallback Place");
    }
}
