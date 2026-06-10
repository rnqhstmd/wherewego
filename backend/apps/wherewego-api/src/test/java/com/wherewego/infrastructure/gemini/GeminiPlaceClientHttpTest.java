package com.wherewego.infrastructure.gemini;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.config.env.PlaceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GeminiPlaceClient#extractPlaceName} HTTP 계약 검증.
 *
 * <p>Spring 컨텍스트 미로드(NFR-1). WireMock 으로 Gemini 응답을 인터셉트하여
 * 정상/literal null/429/500/timeout 5가지 outcome 에 대한 캐시 적재 정책을 검증한다.</p>
 */
@DisplayName("GeminiPlaceClient.extractPlaceName HTTP 계약")
class GeminiPlaceClientHttpTest {

    private static final String GENERATE_CONTENT_PATH =
            "/v1beta/models/gemini-2.0-flash:generateContent";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private GeminiPlaceClient client;
    private GeminiResponseCacheService cacheService;
    private GeminiUserQuotaService quotaService;
    private GeminiUsageMetrics metrics;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();

        PlaceProperties.Gemini gemini = new PlaceProperties.Gemini(
                true, "dummy-key", wireMock.baseUrl(), 100, 50);
        PlaceProperties.Scraper scraper = new PlaceProperties.Scraper(
                new PlaceProperties.InstagramScraper(1000), gemini);
        PlaceProperties placeProperties = new PlaceProperties(
                new PlaceProperties.Instagram(false, true, 5_000L),
                new PlaceProperties.Search(4500L, 5, 1700L, 15000L),
                scraper);

        quotaService = mock(GeminiUserQuotaService.class);
        when(quotaService.tryConsume(anyLong())).thenReturn(true);

        cacheService = mock(GeminiResponseCacheService.class);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(cacheService.hashKey(anyString())).thenReturn("hash");

        metrics = mock(GeminiUsageMetrics.class);

        client = new GeminiPlaceClient(placeProperties, quotaService, cacheService, metrics);
    }

    @Test
    @DisplayName("(AC-10) Gemini 200 + 장소명 응답 → Optional.of(장소명) + 캐시 적재")
    void returnsPlaceNameOn200() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson("\\\"스타벅스 강남점\\\""))));

        // act
        Optional<String> result = client.extractPlaceName("스타벅스 강남점에 다녀왔어요", 1L);

        // assert
        assertThat(result).contains("스타벅스 강남점");
        verify(cacheService, atLeastOnce()).put(anyString(), any());
    }

    @Test
    @DisplayName("(AC-11) Gemini 200 + literal \"null\" 응답 → Optional.empty + 캐시 적재")
    void returnsEmptyOnLiteralNull() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson("null"))));

        // act
        Optional<String> result = client.extractPlaceName("장소가 없는 캡션", 1L);

        // assert
        assertThat(result).isEmpty();
        verify(cacheService, atLeastOnce()).put(anyString(), any());
    }

    @Test
    @DisplayName("(AC-12) Gemini 429 → Optional.empty + 캐시 미적재")
    void returnsEmptyOn429AndNoCache() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(429)));

        // act
        Optional<String> result = client.extractPlaceName("rate limit 캡션", 1L);

        // assert
        assertThat(result).isEmpty();
        verify(cacheService, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("(AC-13) Gemini 500 → Optional.empty + 캐시 미적재")
    void returnsEmptyOn500AndNoCache() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(500)));

        // act
        Optional<String> result = client.extractPlaceName("server error 캡션", 1L);

        // assert
        assertThat(result).isEmpty();
        verify(cacheService, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("(AC-14) timeout (fixedDelay 500 > timeoutMs 100) → Optional.empty + 캐시 미적재")
    void returnsEmptyOnTimeoutAndNoCache() {
        // arrange : 5배 마진(설계 3-2.3)으로 CI 노이즈 흡수
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson("\\\"느린 응답\\\""))
                        .withFixedDelay(500)));

        // act
        Optional<String> result = client.extractPlaceName("timeout 캡션", 1L);

        // assert
        assertThat(result).isEmpty();
        verify(cacheService, never()).put(anyString(), any());
    }

    private static String responseJson(String escapedText) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escapedText + "\"}]}}]}";
    }
}
