package com.wherewego.infrastructure.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.place.PlaceCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
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
@RefreshScope
public class GeminiPlaceClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiPlaceClient.class);
    // gemini-2.5-flash는 thinking 모델이라 reasoning에 토큰을 다 써서 답변이 잘림.
    // gemini-flash-latest는 alias로 비-thinking 동작 + 한국어 답변 안정적.
    private static final String GENERATE_CONTENT_PATH =
            "/v1beta/models/gemini-flash-latest:generateContent";
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

    /** N개 장소 추출 + confident 판단 JSON 프롬프트. */
    private static final String PROMPT_TEMPLATE_MULTI = """
            너는 인스타그램 캡션에서 실제 방문 가능한 장소(가게/음식점/카페/공원/관광지/숙소 등)를
            모두 빠짐없이 추출하는 역할이야. 각 장소에 대해 confident(신뢰도) 판단도 같이 한다.

            중요 규칙:
            - 캡션 처음부터 끝까지 빠짐없이 읽고, 모든 구체적 장소명을 최대 %d개까지 추출.
            - 첫 번째 장소만 뽑지 말고 캡션 전체를 훑어라.
            - 응답은 반드시 다음 JSON 한 줄. 다른 텍스트/설명/이모지 금지.
              {"places": [{"name": "장소명", "confident": true_or_false}]}
            - 장소가 없으면 {"places": []}
            - 같은 장소 중복 제거.
            - 단순 도시명("서울", "도쿄")만 있고 구체적 가게/명소 없으면 그것은 비워둘 것.
            - 단순 감상/형용사("좋은 카페")는 제외, 구체적 이름이 있는 것만.

            name 작성 규칙:
            - 가능한 한 "지역 + 상호명" 또는 "상호명 + 지점"으로 구체적이게.
              예: "스타벅스" 보다는 "스타벅스 강남역점", "메타세콰이어길" 보다는 "하남 메타세콰이어길".
            - 캡션에 지역 힌트가 있으면 같이 포함.

            confident 판단:
            - true: 지역명+상호명 조합 등 동명 다수 가능성 거의 없음 (예: "하남 메타세콰이어길", "스타벅스 강남역점", "코엑스").
            - false: 일반적이거나 전국에 동명 다수 있을 가능성 (예: "스타벅스", "메타세콰이어길", "솔향 카페").

            예시 1.
            캡션: "성수 베이커리 카페 A 다녀왔고, 옆 골목 B 빵집도 들렀어. 마지막은 식당 C에서 마무리!"
            응답: {"places": [{"name": "성수 베이커리 카페 A", "confident": true}, {"name": "성수 B 빵집", "confident": true}, {"name": "식당 C", "confident": false}]}

            예시 2.
            캡션: "하남 메타세콰이어길 산책 → 솔향 카페 → 부석사 맛집 김치찌개"
            응답: {"places": [{"name": "하남 메타세콰이어길", "confident": true}, {"name": "솔향 카페", "confident": false}, {"name": "부석사 김치찌개 맛집", "confident": false}]}

            예시 3.
            캡션: "오늘 너무 좋은 하루였어 #힐링 #일상"
            응답: {"places": []}

            이제 다음 캡션에서 추출:
            %s
            """;
    // 10개 한국어 장소명 + JSON syntax + 안전 여유분.
    // 한국어는 1글자가 보통 2~3 토큰 차지 → 10개 × 평균 15자 × 3 = 450 + JSON ~ 800 정도면 안전.
    private static final int MAX_OUTPUT_TOKENS_MULTI = 1024;
    private static final int DEFAULT_MAX_PLACES = 10;

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
        // @RefreshScope를 통해 baseUrl과 timeoutMs의 런타임 갱신을 지원한다.
        // POST /actuator/refresh 호출 시 이 빈이 재생성되며 RestClient도 새 설정으로 초기화된다.
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
    /**
     * 캡션에서 최대 {@code maxCount}개의 장소명 + confident 판단을 추출한다.
     * 새 흐름의 메인 진입점.
     */
    public java.util.List<PlaceCandidate> extractPlaceCandidates(
            String caption, Long userId, int maxCount) {
        // 기존 extractPlaceNames 호출하여 List<String> 받은 다음, raw JSON을 다시 파싱하기 어려우니
        // 이 메서드는 별도 API 호출하지 않고 같은 응답을 List<PlaceCandidate>로 파싱한다.
        // 호출/파싱 통합을 위해 내부에 별도 구현체를 둔다.
        return extractCandidatesInternal(caption, userId, maxCount);
    }

    private java.util.List<PlaceCandidate> extractCandidatesInternal(
            String caption, Long userId, int maxCount) {
        if (caption == null || caption.isBlank()) return List.of();
        if (!placeProperties.scraper().gemini().enabled()) {
            metrics.recordCall(OUTCOME_DISABLED);
            return List.of();
        }
        int cap = Math.max(1, Math.min(maxCount, DEFAULT_MAX_PLACES));
        String safeCaption = caption.length() > CAPTION_MAX_LENGTH
                ? caption.substring(0, CAPTION_MAX_LENGTH)
                : caption;
        safeCaption = normalize(safeCaption);

        if (!userQuotaService.tryConsume(userId)) {
            log.warn("Gemini quota exceeded (candidates) userId={}", userId);
            metrics.recordCall(OUTCOME_QUOTA_EXCEEDED);
            return List.of();
        }

        String prompt = PROMPT_TEMPLATE_MULTI.formatted(cap, safeCaption);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "maxOutputTokens", MAX_OUTPUT_TOKENS_MULTI,
                        "temperature", TEMPERATURE,
                        "thinkingConfig", Map.of("thinkingBudget", 0)
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
                        throw new GeminiRateLimitException();
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        log.warn("Gemini API error (candidates) status={}", res.getStatusCode());
                        throw new GeminiResponseException();
                    })
                    .body(GeminiGenerateContentResponse.class);
            if (response != null && response.candidates() != null
                    && !response.candidates().isEmpty()
                    && response.candidates().get(0).content() != null
                    && response.candidates().get(0).content().parts() != null
                    && !response.candidates().get(0).content().parts().isEmpty()) {
                String rawText = response.candidates().get(0).content().parts().get(0).text();
                log.info("Gemini candidates raw response: {}", rawText);
            }
            java.util.List<PlaceCandidate> cands = parsePlaceCandidates(response, cap);
            log.info("Gemini candidates parsed size={} list={}", cands.size(), cands);
            outcome = cands.isEmpty() ? OUTCOME_EMPTY : OUTCOME_SUCCESS;
            return cands;
        } catch (GeminiRateLimitException e) {
            outcome = OUTCOME_RATE_LIMITED;
            return List.of();
        } catch (GeminiResponseException e) {
            outcome = OUTCOME_ERROR;
            return List.of();
        } catch (ResourceAccessException e) {
            outcome = isTimeout(e) ? OUTCOME_TIMEOUT : OUTCOME_ERROR;
            return List.of();
        } catch (RestClientException e) {
            outcome = OUTCOME_ERROR;
            return List.of();
        } catch (RuntimeException e) {
            outcome = OUTCOME_ERROR;
            return List.of();
        } finally {
            metrics.recordDuration(System.currentTimeMillis() - start, outcome);
            metrics.recordCall(outcome);
        }
    }

    static java.util.List<PlaceCandidate> parsePlaceCandidates(
            GeminiGenerateContentResponse response, int cap) {
        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()) {
            return List.of();
        }
        var candidate = response.candidates().get(0);
        if (candidate == null
                || candidate.content() == null
                || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            return List.of();
        }
        String text = candidate.content().parts().get(0).text();
        if (text == null || text.isBlank()) return List.of();
        String trimmed = text.trim();
        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart < 0 || objEnd <= objStart) return List.of();
        String jsonPart = trimmed.substring(objStart, objEnd + 1);
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(jsonPart);
            com.fasterxml.jackson.databind.JsonNode arr = root.get("places");
            if (arr == null || !arr.isArray()) return List.of();
            java.util.LinkedHashMap<String, PlaceCandidate> uniq = new java.util.LinkedHashMap<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (uniq.size() >= cap) break;
                if (n == null) continue;
                String name;
                boolean confident;
                if (n.isTextual()) {
                    // 구버전 응답 호환 (단순 string 배열)
                    name = n.asText();
                    confident = false;
                } else if (n.isObject() && n.get("name") != null) {
                    name = n.get("name").asText("");
                    confident = n.has("confident") && n.get("confident").asBoolean(false);
                } else {
                    continue;
                }
                String norm = normalize(stripQuotes(name.trim()));
                if (norm.isEmpty() || norm.equalsIgnoreCase("null")) continue;
                uniq.putIfAbsent(norm, new PlaceCandidate(norm, confident));
            }
            return List.copyOf(uniq.values());
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Gemini candidates JSON parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 캡션에서 최대 {@code maxCount}개의 장소명을 추출한다 (구버전, String 배열만).
     * <p>현재 cache/quota 정책은 단순화: 새 메서드는 user quota만 적용하고 response cache는 우회.
     * 캐시 키 구조를 단일/다중 분리하면 안정성이 떨어질 수 있어 다음 단계에서 통합 예정.</p>
     */
    public List<String> extractPlaceNames(String caption, Long userId, int maxCount) {
        if (caption == null || caption.isBlank()) return List.of();
        if (!placeProperties.scraper().gemini().enabled()) {
            metrics.recordCall(OUTCOME_DISABLED);
            return List.of();
        }
        int cap = Math.max(1, Math.min(maxCount, DEFAULT_MAX_PLACES));
        String safeCaption = caption.length() > CAPTION_MAX_LENGTH
                ? caption.substring(0, CAPTION_MAX_LENGTH)
                : caption;
        safeCaption = normalize(safeCaption);

        if (!userQuotaService.tryConsume(userId)) {
            log.warn("Gemini quota exceeded (multi) userId={}", userId);
            metrics.recordCall(OUTCOME_QUOTA_EXCEEDED);
            return List.of();
        }

        String prompt = PROMPT_TEMPLATE_MULTI.formatted(cap, safeCaption);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "maxOutputTokens", MAX_OUTPUT_TOKENS_MULTI,
                        "temperature", TEMPERATURE,
                        // 2.5/3.x 계열 모델의 reasoning 단계를 끄고 답변만 출력.
                        "thinkingConfig", Map.of("thinkingBudget", 0)
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
                        log.warn("Gemini API rate limited (429, multi)");
                        throw new GeminiRateLimitException();
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        log.warn("Gemini API error (multi) status={}", res.getStatusCode());
                        throw new GeminiResponseException();
                    })
                    .body(GeminiGenerateContentResponse.class);
            // 응답 raw text 디버그용 로그 (장소 추출 누락 진단)
            if (response != null && response.candidates() != null
                    && !response.candidates().isEmpty()
                    && response.candidates().get(0).content() != null
                    && response.candidates().get(0).content().parts() != null
                    && !response.candidates().get(0).content().parts().isEmpty()) {
                String rawText = response.candidates().get(0).content().parts().get(0).text();
                log.info("Gemini multi raw response: {}", rawText);
            }
            List<String> names = parsePlaceNames(response, cap);
            log.info("Gemini multi parsed names size={} names={}", names.size(), names);
            outcome = names.isEmpty() ? OUTCOME_EMPTY : OUTCOME_SUCCESS;
            return names;
        } catch (GeminiRateLimitException e) {
            outcome = OUTCOME_RATE_LIMITED;
            return List.of();
        } catch (GeminiResponseException e) {
            outcome = OUTCOME_ERROR;
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("Gemini API transport/timeout (multi) cause={}", e.getMessage());
            outcome = isTimeout(e) ? OUTCOME_TIMEOUT : OUTCOME_ERROR;
            return List.of();
        } catch (RestClientException e) {
            log.warn("Gemini API client error (multi) cause={}", e.getMessage());
            outcome = OUTCOME_ERROR;
            return List.of();
        } catch (RuntimeException e) {
            log.warn("Gemini API unexpected error (multi) cause={}", e.getMessage());
            outcome = OUTCOME_ERROR;
            return List.of();
        } finally {
            metrics.recordDuration(System.currentTimeMillis() - start, outcome);
            metrics.recordCall(outcome);
        }
    }

    /** JSON 응답 파싱. text가 `{"places":[...]}`인 응답을 List<String>으로 변환. */
    static List<String> parsePlaceNames(GeminiGenerateContentResponse response, int cap) {
        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()) {
            return List.of();
        }
        var candidate = response.candidates().get(0);
        if (candidate == null
                || candidate.content() == null
                || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            return List.of();
        }
        String text = candidate.content().parts().get(0).text();
        if (text == null || text.isBlank()) return List.of();
        // 모델이 ```json ... ``` 코드블록으로 감싸는 경우 대응 — 가장 바깥 `{` ~ `}` 영역만 추출.
        String trimmed = text.trim();
        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart < 0 || objEnd <= objStart) {
            log.warn("Gemini multi response has no JSON object: {}", trimmed);
            return List.of();
        }
        String jsonPart = trimmed.substring(objStart, objEnd + 1);
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(jsonPart);
            com.fasterxml.jackson.databind.JsonNode arr = root.get("places");
            if (arr == null || !arr.isArray()) return List.of();
            java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (uniq.size() >= cap) break;
                if (n == null || !n.isTextual()) continue;
                String s = normalize(stripQuotes(n.asText().trim()));
                if (s.isEmpty() || s.equalsIgnoreCase("null")) continue;
                uniq.add(s);
            }
            return List.copyOf(uniq);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Gemini multi response JSON parse failed: {}", e.getMessage());
            return List.of();
        }
    }

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
