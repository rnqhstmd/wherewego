package com.wherewego.infrastructure.place.kakao;

import com.wherewego.config.env.KakaoApiProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 카카오 Local 키워드 검색 API 호출.
 * <p>baseUrl/타임아웃은 {@link KakaoApiProperties.Local} 기준,
 * Authorization 헤더는 {@code KakaoAK ${kakao.local-api-key}} 형식.</p>
 */
@Component
public class KakaoLocalClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);

    private final KakaoApiProperties properties;
    private final RestClient restClient;

    public KakaoLocalClient(KakaoApiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.local().baseUrl())
                .requestFactory(buildRequestFactory(properties.local().timeoutMs()))
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    /**
     * 키워드 검색 호출. 데드라인 초과 시 {@link CoreException} 발생
     * (호출자 {@code PlaceSearchService} 가 {@code Empty()} 로 변환).
     */
    public List<PlaceSearchHit> searchByKeyword(String keyword, int size, ChatbotContext ctx) {
        if (ctx.expired()) {
            log.warn("Kakao Local search cutoff before request keyword={}", keyword);
            throw new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED, "처리가 지연되었어요. 다시 시도해 주세요.");
        }

        URI uri = UriComponentsBuilder.fromPath("/v2/local/search/keyword.json")
                .queryParam("query", keyword)
                .queryParam("size", size)
                .build()
                .encode()
                .toUri();

        try {
            KakaoLocalSearchResponse response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.localApiKey())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED,
                                "카카오 Local 검색 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED,
                                "카카오 Local 검색 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .body(KakaoLocalSearchResponse.class);

            if (response == null || response.documents() == null) {
                return Collections.emptyList();
            }

            return response.documents().stream()
                    .map(KakaoLocalClient::toHit)
                    .toList();
        } catch (CoreException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Kakao Local search transport error keyword={} cause={}", keyword, e.getMessage());
            throw new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED, "카카오 Local 검색 통신 오류가 발생했습니다.");
        }
    }

    private static PlaceSearchHit toHit(KakaoLocalSearchResponse.Document doc) {
        String address = doc.roadAddressName() != null && !doc.roadAddressName().isBlank()
                ? doc.roadAddressName()
                : doc.addressName();
        Double latitude = parseCoord(doc.y());
        Double longitude = parseCoord(doc.x());
        return new PlaceSearchHit(doc.id(), doc.placeName(), address, latitude, longitude);
    }

    private static Double parseCoord(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
