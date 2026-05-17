package com.wherewego.infrastructure.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wherewego.config.env.PlaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gemini 2.0 Flash REST API 호출. 정제된 캡션에서 장소명 1개를 추출하여 반환한다.
 *
 * <p>엔드포인트: {@code POST .../v1beta/models/gemini-2.0-flash:generateContent}.
 * 인증: {@code x-goog-api-key} 헤더 ({@link PlaceProperties.Gemini#apiKey()}).
 * 타임아웃은 {@link PlaceProperties.Gemini#timeoutMs()} 기준.</p>
 *
 * <p>입력 캡션은 {@value #CAPTION_MAX_LENGTH}자로 절단 후 호출한다 (비용/인젝션 가드).</p>
 *
 * <p>방어선 (Phase 2.5 후속):
 * <ul>
 *     <li>feature flag {@link PlaceProperties.Gemini#enabled()} = false → 즉시 차단</li>
 *     <li>사용자별 일일 호출 한도({@link GeminiUserQuotaService}) 초과 시 차단</li>
 *     <li>SHA-256(safeCaption) 기반 응답 캐시({@link GeminiResponseCacheService})로 중복 호출 절감</li>
 *     <li>호출 outcome / 소요시간 메트릭 발급({@link GeminiUsageMetrics})</li>
 * </ul>
 * </p>
 *
 * <p>BR-1 에러 처리:
 * <ul>
 *     <li>타임아웃(SocketTimeout 래핑된 {@link ResourceAccessException}) → {@link Optional#empty()} + WARN</li>
 *     <li>429 Rate Limit → {@link Optional#empty()} + WARN (캐싱하지 않음)</li>
 *     <li>그 외 4xx/5xx, 파싱 실패 → {@link Optional#empty()} + WARN (예외 throw 금지)</li>
 *     <li>응답 텍스트가 비어있거나 {@code "null"} → {@link Optional#empty()}</li>
 * </ul>
 * </p>
 */
@Component
public class GeminiPlaceClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiPlaceClient.class);
    private static final String GENERATE_CONTENT_PATH =
            "/v1beta/models/gemini-2.0-flash:generateContent";
    private static final int MAX_OUTPUT_TOKENS = 50;
    private static final double TEMPERATURE = 0.0;
    private static final int CAPTION_MAX_LENGTH = 500;
    private static final String PROMPT_TEMPLATE = """
            다음은 인스타그램 게시물 캡션이야.
            가게명 또는 장소명 하나만 추출해줘.
            이모지, 설명 코멘트, 해시태그는 제외하고 이름만.
            장소가 없으면 null 이라고만 답해.

            캡션:
            %s
            """;

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_EMPTY = "empty";
    private static final String OUTCOME_CACHED = "cached";
    private static final String OUTCOME_DISABLED = "disabled";
    private static final String OUTCOME_QUOTA_EXCEEDED = "quota_exceeded";
    private static final String OUTCOME_RATE_LIMITED = "rate_limited";
    private static final String OUTCOME_TIMEOUT = "timeout";
    private static final String OUTCOME_ERROR = "error";

    private final PlaceProperties placeProperties;
    private final RestClient restClient;
    private final GeminiUserQuotaService userQuotaService;
    private final GeminiResponseCacheService responseCache;
    private final GeminiUsageMetrics metrics;

    public GeminiPlaceClient(PlaceProperties placeProperties,
                             GeminiUserQuotaService userQuotaService,
                             GeminiResponseCacheService responseCache,
                             GeminiUsageMetrics metrics) {
        this.placeProperties = placeProperties;
        this.userQuotaService = userQuotaService;
        this.responseCache = responseCache;
        this.metrics = metrics;
        // baseUrl과 timeoutMs는 생성자 시점 1회 캡처된다. PlaceProperties가 @RefreshScope 여도
        // RestClient는 갱신되지 않으므로 운영 변경 시 재기동 필요. (TODO 후속: 런타임 갱신 전략)
        this.restClient = RestClient.builder()
                .baseUrl(placeProperties.scraper().gemini().baseUrl())
                .requestFactory(buildRequestFactory(placeProperties.scraper().gemini().timeoutMs()))
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    /**
     * 정제된 캡션을 Gemini API에 전달하여 장소명 1개를 추출한다.
     * null/blank 입력은 즉시 {@link Optional#empty()} 반환 (Gemini 미호출, 메트릭 미발급).
     */
    public Optional<String> extractPlaceName(String caption, Long userId) {
        if (caption == null || caption.isBlank()) {
            return Optional.empty();
        }

        if (!placeProperties.scraper().gemini().enabled()) {
            metrics.recordCall(OUTCOME_DISABLED);
            return Optional.empty();
        }

        String safeCaption = caption.length() > CAPTION_MAX_LENGTH
                ? caption.substring(0, CAPTION_MAX_LENGTH)
                : caption;
        safeCaption = normalize(safeCaption);

        String cacheKey = responseCache.hashKey(safeCaption);
        Optional<Optional<String>> cached = responseCache.get(cacheKey);
        if (cached.isPresent()) {
            metrics.recordCall(OUTCOME_CACHED);
            return cached.get();
        }

        if (!userQuotaService.tryConsume(userId)) {
            log.warn("Gemini quota exceeded userId={}", userId);
            metrics.recordCall(OUTCOME_QUOTA_EXCEEDED);
            return Optional.empty();
        }

        String prompt = PROMPT_TEMPLATE.formatted(safeCaption);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", MAX_OUTPUT_TOKENS,
                        "temperature", TEMPERATURE
                )
        );

        long start = System.currentTimeMillis();
        String outcome = OUTCOME_ERROR;
        try {
            GeminiGenerateContentResponse response = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(GENERATE_CONTENT_PATH).build())
                    .header("x-goog-api-key", placeProperties.scraper().gemini().apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        log.warn("Gemini API rate limited (429)");
                        throw new GeminiRateLimitException();
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        log.warn("Gemini API error status={}", res.getStatusCode());
                        throw new GeminiResponseException();
                    })
                    .body(GeminiGenerateContentResponse.class);

            ParseResult parsed = parsePlaceName(response);
            if (parsed.cacheable()) {
                responseCache.put(cacheKey, parsed.value());
            }
            outcome = parsed.value().isPresent() ? OUTCOME_SUCCESS : OUTCOME_EMPTY;
            return parsed.value();
        } catch (GeminiRateLimitException e) {
            outcome = OUTCOME_RATE_LIMITED;
            return Optional.empty();
        } catch (GeminiResponseException e) {
            outcome = OUTCOME_ERROR;
            return Optional.empty();
        } catch (ResourceAccessException e) {
            log.warn("Gemini API transport/timeout error cause={}", e.getMessage());
            outcome = isTimeout(e) ? OUTCOME_TIMEOUT : OUTCOME_ERROR;
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Gemini API client error cause={}", e.getMessage());
            outcome = OUTCOME_ERROR;
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Gemini API unexpected error cause={}", e.getMessage());
            outcome = OUTCOME_ERROR;
            return Optional.empty();
        } finally {
            metrics.recordDuration(System.currentTimeMillis() - start, outcome);
            metrics.recordCall(outcome);
        }
    }

    /**
     * Gemini 응답을 파싱하여 장소명과 캐싱 가능 여부를 함께 반환한다.
     *
     * <p>캐싱 정책:
     * <ul>
     *     <li>정상 추출(success) → cacheable=true (장소명 캐싱)</li>
     *     <li>literal {@code "null"} 응답 → cacheable=true (Gemini의 명시적 "장소 없음" 판단을 24h 재사용)</li>
     *     <li>일시 장애(candidates null, parts empty, text null 등) → cacheable=false (24h 재시도 차단 방지)</li>
     * </ul>
     * </p>
     *
     * <p>package-private: 단위 테스트에서 직접 호출 가능.</p>
     */
    static ParseResult parsePlaceName(GeminiGenerateContentResponse response) {
        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()) {
            log.warn("Gemini API response has no candidates");
            return new ParseResult(Optional.empty(), false);
        }
        GeminiGenerateContentResponse.Candidate candidate = response.candidates().get(0);
        if (candidate == null
                || candidate.content() == null
                || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            log.warn("Gemini API response candidate has no parts");
            return new ParseResult(Optional.empty(), false);
        }
        String text = candidate.content().parts().get(0).text();
        if (text == null) {
            return new ParseResult(Optional.empty(), false);
        }
        String cleaned = stripQuotes(text.trim());
        cleaned = normalize(cleaned);
        if (cleaned.isEmpty()) {
            return new ParseResult(Optional.empty(), false);
        }
        if (cleaned.equalsIgnoreCase("null")) {
            return new ParseResult(Optional.empty(), true);
        }
        return new ParseResult(Optional.of(cleaned), true);
    }

    /**
     * 파싱 결과와 응답 캐시 적재 가능 여부를 함께 보관한다.
     *
     * <p>일시 장애(SAFETY 차단, candidates null 등)로 인한 empty는 캐싱하지 않고 재시도를 허용한다.</p>
     */
    record ParseResult(Optional<String> value, boolean cacheable) { }

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

    private static String normalize(String text) {
        return text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
    }

    private static String stripQuotes(String value) {
        String result = value;
        while (!result.isEmpty()) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            boolean firstQuote = first == '"' || first == '\'' || first == '`';
            boolean lastQuote = last == '"' || last == '\'' || last == '`';
            if (firstQuote && lastQuote && result.length() >= 2) {
                result = result.substring(1, result.length() - 1).trim();
                continue;
            }
            if (firstQuote) {
                result = result.substring(1).trim();
                continue;
            }
            if (lastQuote) {
                result = result.substring(0, result.length() - 1).trim();
                continue;
            }
            break;
        }
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeminiGenerateContentResponse(List<Candidate> candidates) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Candidate(Content content) { }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Content(List<Part> parts) { }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Part(String text) { }
    }

    private static final class GeminiRateLimitException extends RuntimeException {
    }

    private static final class GeminiResponseException extends RuntimeException {
    }
}
