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
 * <p>BR-1 에러 처리:
 * <ul>
 *     <li>타임아웃(SocketTimeout 래핑된 {@link ResourceAccessException}) → {@link Optional#empty()} + WARN</li>
 *     <li>429 Rate Limit → {@link Optional#empty()} + WARN</li>
 *     <li>그 외 4xx/5xx, 파싱 실패 → {@link Optional#empty()} + WARN (예외 throw 금지)</li>
 *     <li>응답 텍스트가 비어있거나 {@code "null"} → {@link Optional#empty()}</li>
 * </ul>
 * </p>
 */
@Component
public class GeminiPlaceClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiPlaceClient.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
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

    private final PlaceProperties.Gemini properties;
    private final RestClient restClient;

    public GeminiPlaceClient(PlaceProperties placeProperties) {
        this.properties = placeProperties.scraper().gemini();
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
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
     * 정제된 캡션을 Gemini API에 전달하여 장소명 1개를 추출한다.
     * null/blank 입력은 즉시 {@link Optional#empty()} 반환 (Gemini 미호출).
     */
    public Optional<String> extractPlaceName(String caption) {
        if (caption == null || caption.isBlank()) {
            return Optional.empty();
        }

        String safeCaption = caption.length() > CAPTION_MAX_LENGTH
                ? caption.substring(0, CAPTION_MAX_LENGTH)
                : caption;
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

        try {
            GeminiGenerateContentResponse response = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(GENERATE_CONTENT_PATH).build())
                    .header("x-goog-api-key", properties.apiKey())
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

            return parsePlaceName(response);
        } catch (GeminiRateLimitException | GeminiResponseException e) {
            return Optional.empty();
        } catch (ResourceAccessException e) {
            log.warn("Gemini API transport/timeout error cause={}", e.getMessage());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Gemini API client error cause={}", e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Gemini API unexpected error cause={}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> parsePlaceName(GeminiGenerateContentResponse response) {
        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()) {
            log.warn("Gemini API response has no candidates");
            return Optional.empty();
        }
        GeminiGenerateContentResponse.Candidate candidate = response.candidates().get(0);
        if (candidate == null
                || candidate.content() == null
                || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            log.warn("Gemini API response candidate has no parts");
            return Optional.empty();
        }
        String text = candidate.content().parts().get(0).text();
        if (text == null) {
            return Optional.empty();
        }
        String cleaned = stripQuotes(text.trim());
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("null")) {
            return Optional.empty();
        }
        return Optional.of(cleaned);
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
