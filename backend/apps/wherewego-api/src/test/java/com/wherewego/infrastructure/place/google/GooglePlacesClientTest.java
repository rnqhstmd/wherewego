package com.wherewego.infrastructure.place.google;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.config.cache.CacheConfig;
import com.wherewego.config.env.GooglePlacesProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GooglePlacesClient.searchByKeyword 를 호출할 때,")
class GooglePlacesClientTest {

    private static final String SEARCH_PATH = "/v1/places:searchText";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private GooglePlacesClient client;
    private GooglePlacesResponseCacheService cacheService;
    private GooglePlacesMetrics metrics;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        GooglePlacesProperties properties =
                new GooglePlacesProperties("test-key", wireMock.baseUrl(), 500);
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(CacheConfig.GOOGLE_PLACES_RESPONSE_CACHE,
                Caffeine.newBuilder().build());
        cacheService = new GooglePlacesResponseCacheService(cacheManager);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new GooglePlacesMetrics(meterRegistry);
        client = new GooglePlacesClient(properties, cacheService, metrics);
    }

    private static ChatbotContext freshContext() {
        return ChatbotContext.start(5_000);
    }

    @Nested
    @DisplayName("정상 응답을 받았을 때,")
    class WhenOk {

        @DisplayName("응답 200 + places 1건이면 PlaceSearchHit 1건을 반환한다.")
        @Test
        void ok_singlePlace_returnsOneHit() {
            // arrange
            String body = """
                    {
                      "places": [
                        {
                          "id": "places/abc",
                          "displayName": { "text": "스타벅스", "languageCode": "ko" },
                          "formattedAddress": "서울시 강남구",
                          "location": { "latitude": 37.5, "longitude": 127.0 }
                        }
                      ]
                    }
                    """;
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));

            // act
            List<PlaceSearchHit> hits = client.searchByKeyword("스타벅스", 5, freshContext());

            // assert
            assertThat(hits).hasSize(1);
            PlaceSearchHit hit = hits.get(0);
            assertThat(hit.kakaoPlaceId()).isEqualTo("places/abc");
            assertThat(hit.placeName()).isEqualTo("스타벅스");
            assertThat(hit.address()).isEqualTo("서울시 강남구");
            assertThat(hit.latitude()).isEqualTo(37.5);
            assertThat(hit.longitude()).isEqualTo(127.0);
        }

        @DisplayName("응답 200 + places 가 비어 있으면 빈 리스트를 반환한다.")
        @Test
        void ok_emptyPlaces_returnsEmpty() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"places\": []}")));

            // act
            List<PlaceSearchHit> hits = client.searchByKeyword("없는장소", 5, freshContext());

            // assert
            assertThat(hits).isEmpty();
        }
    }

    @Nested
    @DisplayName("실패 응답을 받았을 때,")
    class WhenFailure {

        @DisplayName("응답 4xx 이면 CoreException(PLC_GOOGLE_PLACES_FAILED) 을 던진다.")
        @Test
        void clientError_throwsCoreException() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"bad request\"}")));

            // act & assert
            assertThatThrownBy(() -> client.searchByKeyword("키워드", 5, freshContext()))
                    .isInstanceOf(CoreException.class)
                    .extracting(e -> ((CoreException) e).getErrorType())
                    .isEqualTo(ErrorType.PLC_GOOGLE_PLACES_FAILED);
        }

        @DisplayName("응답 5xx 이면 CoreException(PLC_GOOGLE_PLACES_FAILED) 을 던진다.")
        @Test
        void serverError_throwsCoreException() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withStatus(503)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"unavailable\"}")));

            // act & assert
            assertThatThrownBy(() -> client.searchByKeyword("키워드", 5, freshContext()))
                    .isInstanceOf(CoreException.class)
                    .extracting(e -> ((CoreException) e).getErrorType())
                    .isEqualTo(ErrorType.PLC_GOOGLE_PLACES_FAILED);
        }

        @DisplayName("응답 지연이 타임아웃을 초과하면 CoreException(PLC_GOOGLE_PLACES_FAILED) 을 던진다.")
        @Test
        void timeout_throwsCoreException() {
            // arrange : timeoutMs=500 인데 1500ms 지연
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withFixedDelay(1_500)
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"places\": []}")));

            // act & assert
            assertThatThrownBy(() -> client.searchByKeyword("키워드", 5, freshContext()))
                    .isInstanceOf(CoreException.class)
                    .extracting(e -> ((CoreException) e).getErrorType())
                    .isEqualTo(ErrorType.PLC_GOOGLE_PLACES_FAILED);
        }
    }

    @Nested
    @DisplayName("컨텍스트가 만료되었을 때,")
    class WhenContextExpired {

        @DisplayName("ctx 가 이미 만료되었으면 호출 전에 CoreException 을 던진다.")
        @Test
        void expiredContext_throwsBeforeRequest() {
            // arrange : deadline 0ms → 즉시 expired
            ChatbotContext expired = ChatbotContext.start(0);

            // act & assert
            assertThatThrownBy(() -> client.searchByKeyword("키워드", 5, expired))
                    .isInstanceOf(CoreException.class)
                    .extracting(e -> ((CoreException) e).getErrorType())
                    .isEqualTo(ErrorType.PLC_GOOGLE_PLACES_FAILED);

            // WireMock 으로의 호출이 전혀 발생하지 않아야 한다.
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    /**
     * Phase 2.11 PR-B B5 보강 — 캐시/메트릭 통합 케이스.
     *
     * <p>cacheService/metrics 를 Mockito 로 감싸 외부 HTTP 호출 여부, put/recordCall 호출, 예외 격리를 검증한다.</p>
     */
    @Nested
    @DisplayName("캐시/메트릭 통합 동작에서,")
    class CacheAndMetrics {

        private GooglePlacesResponseCacheService cacheMock;
        private GooglePlacesMetrics metricsMock;
        private GooglePlacesClient mockedClient;

        @BeforeEach
        void setUpMocks() {
            wireMock.resetAll();
            GooglePlacesProperties properties =
                    new GooglePlacesProperties("test-key", wireMock.baseUrl(), 500);
            cacheMock = mock(GooglePlacesResponseCacheService.class);
            metricsMock = mock(GooglePlacesMetrics.class);
            when(cacheMock.hashKey(anyString())).thenReturn("h1");
            mockedClient = new GooglePlacesClient(properties, cacheMock, metricsMock);
        }

        @DisplayName("(AC-2) 캐시 hit 이면 외부 HTTP 호출 없이 캐시 값을 반환하고 metrics.recordCall(\"cached\") 가 호출된다.")
        @Test
        void cacheHit_returnsCachedValue_andRecordsCached() {
            // arrange
            PlaceSearchHit hit = new PlaceSearchHit("p/1", "스타벅스", "서울", 37.5, 127.0);
            when(cacheMock.get("h1")).thenReturn(Optional.of(List.of(hit)));

            // act
            List<PlaceSearchHit> result = mockedClient.searchByKeyword("스타벅스", 5, freshContext());

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).kakaoPlaceId()).isEqualTo("p/1");
            verify(metricsMock, times(1)).recordCall("cached");
            // 외부 HTTP 호출 0 회
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }

        @DisplayName("(AC-2, AC-3) 캐시 miss + 외부 200 응답 → cacheService.put 1회 호출.")
        @Test
        void cacheMiss_success_callsPut() {
            // arrange
            when(cacheMock.get("h1")).thenReturn(Optional.empty());
            String body = """
                    {
                      "places": [
                        {
                          "id": "places/abc",
                          "displayName": { "text": "스타벅스", "languageCode": "ko" },
                          "formattedAddress": "서울시 강남구",
                          "location": { "latitude": 37.5, "longitude": 127.0 }
                        }
                      ]
                    }
                    """;
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));

            // act
            List<PlaceSearchHit> hits = mockedClient.searchByKeyword("스타벅스", 5, freshContext());

            // assert
            assertThat(hits).hasSize(1);
            ArgumentCaptor<List<PlaceSearchHit>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(cacheMock, times(1)).put(any(), captor.capture());
            assertThat(captor.getValue()).hasSize(1);
        }

        @DisplayName("(AC-4) 캐시 miss + 외부 429 응답 → CoreException + cacheService.put 미호출.")
        @Test
        void cacheMiss_rateLimited_doesNotPut() {
            // arrange
            when(cacheMock.get("h1")).thenReturn(Optional.empty());
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse().withStatus(429)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"rate\"}")));

            // act & assert
            assertThatThrownBy(() -> mockedClient.searchByKeyword("키워드", 5, freshContext()))
                    .isInstanceOf(CoreException.class);
            verify(cacheMock, never()).put(any(), any());
        }

        @DisplayName("(AC-15) cacheService.get 이 RuntimeException 을 던져도 미스로 진행하여 외부 호출 후 정상 반환한다.")
        @Test
        void cacheGetThrows_proceedsAsMiss() {
            // arrange
            doThrow(new RuntimeException("cache boom")).when(cacheMock).get(anyString());
            String body = """
                    {
                      "places": [
                        {
                          "id": "places/abc",
                          "displayName": { "text": "스타벅스", "languageCode": "ko" },
                          "formattedAddress": "서울시 강남구",
                          "location": { "latitude": 37.5, "longitude": 127.0 }
                        }
                      ]
                    }
                    """;
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));

            // act
            List<PlaceSearchHit> hits = mockedClient.searchByKeyword("스타벅스", 5, freshContext());

            // assert
            assertThat(hits).hasSize(1);
            wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }

        @DisplayName("(AC-16) metrics.recordCall 이 RuntimeException 을 던져도 searchByKeyword 반환값(List)이 그대로 유지된다.")
        @Test
        void metricsRecordCallThrows_returnValueUnchanged() {
            // arrange
            when(cacheMock.get("h1")).thenReturn(Optional.empty());
            doThrow(new RuntimeException("metrics boom")).when(metricsMock).recordCall(anyString());
            String body = """
                    {
                      "places": [
                        {
                          "id": "places/xyz",
                          "displayName": { "text": "메가커피", "languageCode": "ko" },
                          "formattedAddress": "서울시 종로구",
                          "location": { "latitude": 37.6, "longitude": 127.1 }
                        }
                      ]
                    }
                    """;
            wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));

            // act
            List<PlaceSearchHit> hits = mockedClient.searchByKeyword("메가커피", 5, freshContext());

            // assert
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).kakaoPlaceId()).isEqualTo("places/xyz");
        }
    }
}
