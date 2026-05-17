package com.wherewego.infrastructure.place.google;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.config.env.GooglePlacesProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GooglePlacesClient.searchByKeyword 를 호출할 때,")
class GooglePlacesClientTest {

    private static final String SEARCH_PATH = "/v1/places:searchText";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private GooglePlacesClient client;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        GooglePlacesProperties properties =
                new GooglePlacesProperties("test-key", wireMock.baseUrl(), 500);
        client = new GooglePlacesClient(properties);
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
}
