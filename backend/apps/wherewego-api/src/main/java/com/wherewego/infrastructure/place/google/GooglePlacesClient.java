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
import java.util.Optional;

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

    private static final String API_NAME = "google_places";
    private static final String OP_SEARCH_TEXT = "searchText";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_EMPTY = "empty";
    private static final String OUTCOME_RATE_LIMITED = "rate_limited";
    private static final String OUTCOME_TIMEOUT = "timeout";
    private static final String OUTCOME_ERROR = "error";
    private static final String OUTCOME_CACHED = "cached";
    private static final String CACHE_NA = "n/a";
    private static final String CACHE_HIT = "hit";
    private static final String CACHE_MISS = "miss";

    private final GooglePlacesProperties properties;
    private final GooglePlacesResponseCacheService responseCache;
    private final GooglePlacesMetrics metrics;
    private final RestClient restClient;

    public GooglePlacesClient(
            GooglePlacesProperties properties,
            GooglePlacesResponseCacheService responseCache,
            GooglePlacesMetrics metrics) {
        this.properties = properties;
        this.responseCache = responseCache;
        this.metrics = metrics;
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
        // keyword 가 null/blank 면 외부 호출 자체가 의미 없음 + Map.of NPE 방지.
        if (keyword == null || keyword.isBlank()) {
            throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                    "검색어가 비어 있어요.");
        }

        // FR-OBS-9: 캐시 조회 (NFR-2: get 실패는 미스로 대체)
        String keyHash = null;
        try {
            keyHash = responseCache.hashKey(keyword);
        } catch (RuntimeException e) {
            log.warn("GooglePlaces cache hashKey failed: {}", e.getMessage());
        }
        if (keyHash != null) {
            Optional<List<PlaceSearchHit>> cached = Optional.empty();
            try {
                cached = responseCache.get(keyHash);
            } catch (RuntimeException e) {
                log.warn("GooglePlaces cache get failed: {}", e.getMessage());
            }
            if (cached.isPresent()) {
                // NFR-3: metrics 실패가 본 흐름 막지 않음
                try {
                    metrics.recordCall(OUTCOME_CACHED);
                } catch (RuntimeException e) {
                    log.warn("GooglePlaces metrics recordCall(cached) failed: {}", e.getMessage());
                }
                log.info("api={} op={} duration_ms={} outcome={} cache={}",
                        API_NAME, OP_SEARCH_TEXT, 0, OUTCOME_CACHED, CACHE_HIT);
                return cached.get();
            }
        }

        long start = System.currentTimeMillis();
        String outcome = OUTCOME_ERROR;
        boolean cachePut = false;
        try {
            if (ctx.expired()) {
                outcome = OUTCOME_TIMEOUT;
                log.warn("Google Places search cutoff before request keyword={}", safeForLog(keyword));
                throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                        "처리가 지연되었어요. 다시 시도해 주세요.");
            }

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
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                                "Google Places 검색 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                                "Google Places 검색 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                                "Google Places 검색 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .body(GooglePlacesSearchResponse.class);

            // 응답이 null인 경우(Google Places API가 200을 반환했으나 본문 미응답): 빈 결과로 간주하여 캐싱.
            // 일시 장애가 아닌 "검색 결과 없음" 응답의 변형으로 처리한다.
            if (response == null) {
                outcome = OUTCOME_EMPTY;
                if (keyHash != null) {
                    try {
                        responseCache.put(keyHash, List.of());
                        cachePut = true; // put 성공 시에만 cache=miss 로 기록
                    } catch (RuntimeException e) {
                        log.warn("GooglePlaces cache put failed: {}", e.getMessage());
                    }
                }
                return List.of();
            }
            List<PlaceSearchHit> hits = response.toHits(size);
            outcome = hits.isEmpty() ? OUTCOME_EMPTY : OUTCOME_SUCCESS;
            if (keyHash != null) {
                try {
                    responseCache.put(keyHash, hits);
                    cachePut = true; // put 성공 시에만 cache=miss 로 기록
                } catch (RuntimeException e) {
                    log.warn("GooglePlaces cache put failed: {}", e.getMessage());
                }
            }
            return hits;
        } catch (CoreException e) {
            // ctx.expired() 분기에서 미리 설정한 OUTCOME_TIMEOUT은 보존, 그 외는 분류
            if (!OUTCOME_TIMEOUT.equals(outcome)) {
                outcome = classifyOutcome(e);
            }
            throw e;
        } catch (RestClientException e) {
            log.warn("Google Places transport error keyword={} cause={}", safeForLog(keyword), e.getMessage());
            outcome = isTimeout(e) ? OUTCOME_TIMEOUT : OUTCOME_ERROR;
            throw new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED,
                    "Google Places 검색 통신 오류가 발생했습니다.");
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            // NFR-3: metrics 실패가 본 반환/예외에 영향 없음.
            // GeminiPlaceClient와 동일한 순서(recordDuration → recordCall)로 통일.
            try {
                metrics.recordDuration(elapsed, outcome);
                metrics.recordCall(outcome);
            } catch (RuntimeException e) {
                log.warn("GooglePlaces metrics record failed: {}", e.getMessage());
            }
            String cacheField = cachePut ? CACHE_MISS : CACHE_NA;
            log.info("api={} op={} duration_ms={} outcome={} cache={}",
                    API_NAME, OP_SEARCH_TEXT, elapsed, outcome, cacheField);
        }
    }

    private static String classifyOutcome(CoreException e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("status=429")) {
            return OUTCOME_RATE_LIMITED;
        }
        return OUTCOME_ERROR;
    }

    /**
     * 로그 인젝션 방지: 외부 입력(keyword 등) 내 CRLF를 무력화하여 로그 라인 위변조를 차단한다.
     */
    private static String safeForLog(String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }

    private static boolean isTimeout(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
