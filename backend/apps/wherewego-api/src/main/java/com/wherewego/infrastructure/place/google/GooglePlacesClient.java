package com.wherewego.infrastructure.place.google;

import com.wherewego.config.env.GooglePlacesProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Google Places API (New) Text Search 호출.
 * <p>baseUrl/타임아웃은 {@link GooglePlacesProperties}.
 * 인증 헤더는 {@code X-Goog-Api-Key}, 응답 필드는 {@code X-Goog-FieldMask}로 한정한다.</p>
 *
 * <p>실패 시 {@link CoreException}({@link ErrorType#PLC_GOOGLE_PLACES_FAILED}) 으로 래핑하여
 * 호출자({@code PlaceFallbackOrchestrator}) 가 Empty 로 변환한다.</p>
 */
@Component
public class GooglePlacesClient {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesClient.class);
    private static final String FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location";

    private final GooglePlacesProperties properties;
    private final RestClient restClient;

    public GooglePlacesClient(GooglePlacesProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(buildRequestFactory(properties.timeoutMs()))
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    /**
     * 키워드로 Google Places Text Search 호출. 데드라인 초과 시 {@link CoreException}.
     */
    public List<PlaceSearchHit> searchByKeyword(String keyword, int size, ChatbotContext ctx) {
        if (ctx.expired()) {
            log.warn("Google Places search cutoff before request keyword={}", keyword);
            throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                    "처리가 지연되었어요. 다시 시도해 주세요.");
        }

        try {
            GooglePlacesSearchResponse response = restClient.post()
                    .uri("/v1/places:searchText")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .body(Map.of(
                            "textQuery", keyword,
                            "languageCode", "ko",
                            "regionCode", "KR"
                    ))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                                "Google Places 검색 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                                "Google Places 검색 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .body(GooglePlacesSearchResponse.class);

            if (response == null) {
                return List.of();
            }
            return response.toHits(size);
        } catch (CoreException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Google Places transport error keyword={} cause={}", keyword, e.getMessage());
            throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                    "Google Places 검색 통신 오류가 발생했습니다.");
        }
    }
}
