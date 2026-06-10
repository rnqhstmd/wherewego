package com.wherewego.infrastructure.gemini;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.config.env.PlaceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gemini 5xx 응답 시 outcome=server_error 카운터 증가 검증 (FR-OBS-8-pre).
 *
 * <p>3개 메서드(extractPlaceName / extractPlaceCandidates / extractPlaceNames) 각각 WireMock 으로
 * 5xx 응답을 인터셉트하고, 4xx(rate_limited/error) 분기와의 상호 불간섭을 확인한다.</p>
 */
@DisplayName("GeminiPlaceClient 5xx → outcome=server_error 메트릭")
class GeminiPlaceClientServerErrorTest {

    private static final String GENERATE_CONTENT_PATH =
            "/v1beta/models/gemini-flash-latest:generateContent";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private MeterRegistry meterRegistry;
    private GeminiUsageMetrics realMetrics;
    private GeminiPlaceClient client;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();

        PlaceProperties.Gemini gemini = new PlaceProperties.Gemini(
                true, "dummy-key", wireMock.baseUrl(), 2_000, 50);
        PlaceProperties.Scraper scraper = new PlaceProperties.Scraper(
                new PlaceProperties.InstagramScraper(1_000), gemini);
        PlaceProperties placeProperties = new PlaceProperties(
                new PlaceProperties.Instagram(false),
                new PlaceProperties.Search(4_500L, 5, 1_700L, 15_000L),
                scraper);

        GeminiUserQuotaService quotaService = mock(GeminiUserQuotaService.class);
        when(quotaService.tryConsume(anyLong())).thenReturn(true);

        GeminiResponseCacheService cacheService = mock(GeminiResponseCacheService.class);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(cacheService.hashKey(anyString())).thenReturn("hash");

        meterRegistry = new SimpleMeterRegistry();
        realMetrics = new GeminiUsageMetrics(meterRegistry);

        client = new GeminiPlaceClient(placeProperties, quotaService, cacheService, realMetrics);
    }

    private double counter(String outcome) {
        var c = meterRegistry.find("gemini.calls.total").tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }

    @DisplayName("(AC-6) extractPlaceName + WireMock 500 → server_error +1, error 불변")
    @Test
    void extractPlaceName_500_incrementsServerError() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(500)));

        // act
        Optional<String> result = client.extractPlaceName("server error caption", 1L);

        // assert
        assertThat(result).isEmpty();
        assertThat(counter("server_error")).isEqualTo(1.0);
        assertThat(counter("error")).isEqualTo(0.0);
    }

    @DisplayName("extractPlaceCandidates + WireMock 502 → server_error +1, error 불변")
    @Test
    void extractPlaceCandidates_502_incrementsServerError() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(502)));

        // act
        var result = client.extractPlaceCandidates("bad gateway caption", 1L, 5);

        // assert
        assertThat(result).isEmpty();
        assertThat(counter("server_error")).isEqualTo(1.0);
        assertThat(counter("error")).isEqualTo(0.0);
    }

    @DisplayName("extractPlaceNames + WireMock 503 → server_error +1, error 불변")
    @Test
    void extractPlaceNames_503_incrementsServerError() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(503)));

        // act
        List<String> result = client.extractPlaceNames("unavailable caption", 1L, 5);

        // assert
        assertThat(result).isEmpty();
        assertThat(counter("server_error")).isEqualTo(1.0);
        assertThat(counter("error")).isEqualTo(0.0);
    }

    @DisplayName("(AC-7) 429 응답 → rate_limited +1, server_error 불변")
    @Test
    void extractPlaceName_429_incrementsRateLimitedNotServerError() {
        // arrange
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(429)));

        // act
        Optional<String> result = client.extractPlaceName("rate limit caption", 1L);

        // assert
        assertThat(result).isEmpty();
        assertThat(counter("rate_limited")).isEqualTo(1.0);
        assertThat(counter("server_error")).isEqualTo(0.0);
    }

    @DisplayName("(BR-1) 400 (non-5xx 4xx) → error +1, server_error 불변 — 4xx 는 server_error 분류에서 제외.")
    @Test
    void extractPlaceName_400_classification() {
        // arrange
        // GeminiPlaceClient 의 onStatus 가 4xx(429 제외)를 GeminiClientErrorException 으로 분리하여
        // OUTCOME_ERROR 로 분류한다. server_error 는 5xx 만 담당 (BR-1).
        wireMock.stubFor(post(urlPathEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(400)));

        // act
        Optional<String> result = client.extractPlaceName("bad request caption", 1L);

        // assert : 4xx 비-429 는 error 로 분류, server_error 는 불변
        assertThat(result).isEmpty();
        assertThat(counter("error")).isEqualTo(1.0);
        assertThat(counter("server_error")).isEqualTo(0.0);
        assertThat(counter("rate_limited")).isEqualTo(0.0);
    }
}
