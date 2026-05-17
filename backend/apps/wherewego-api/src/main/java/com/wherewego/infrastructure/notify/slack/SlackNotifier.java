package com.wherewego.infrastructure.notify.slack;

import com.wherewego.config.env.SlackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Slack Incoming Webhook 알림 발송.
 *
 * <p>기존 logback ASYNC-SLACK 어펜더와 별개로, context map 첨부가 필요한
 * 비동기 폴백 실패 케이스에 사용한다. {@code slack.webhook-uri}가 비어 있으면
 * 안전한 no-op (dev/test 환경). 모든 예외는 swallow + log.warn.</p>
 */
@Component
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    private static final int TIMEOUT_MS = 2_000;

    private final SlackProperties properties;
    private final RestClient restClient;
    private final boolean strictSchemeCheck;

    @Autowired
    public SlackNotifier(SlackProperties properties) {
        this(properties, true);
    }

    /**
     * 테스트 전용 생성자. {@code strictSchemeCheck=false}로 https 스킴 검증을 우회한다.
     * WireMock baseUrl(http://localhost:포트) 통합 검증에 한해 사용한다.
     */
    SlackNotifier(SlackProperties properties, boolean strictSchemeCheck) {
        this.properties = properties;
        this.strictSchemeCheck = strictSchemeCheck;
        this.restClient = RestClient.builder()
                .requestFactory(buildRequestFactory())
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(TIMEOUT_MS));
        return factory;
    }

    /**
     * 실패 알림 발송. context map의 key-value를 Slack 본문에 인라인으로 첨부한다.
     * webhookUri가 비어 있으면 no-op.
     */
    public void notifyFailure(String title, Map<String, Object> context) {
        String uri = properties.webhookUri();
        if (uri == null || uri.isBlank()) {
            return;
        }
        URI parsedUri;
        try {
            parsedUri = URI.create(uri);
            if (strictSchemeCheck && !"https".equalsIgnoreCase(parsedUri.getScheme())) {
                log.warn("Slack webhookUri scheme not allowed");
                return;
            }
        } catch (Exception e) {
            log.warn("Slack webhookUri parse failed cause={}", e.getMessage());
            return;
        }
        try {
            StringBuilder text = new StringBuilder();
            text.append("[").append(properties.username() == null ? "wherewego" : properties.username()).append("] ");
            text.append(title);
            if (context != null && !context.isEmpty()) {
                text.append("\n");
                for (Map.Entry<String, Object> e : context.entrySet()) {
                    text.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text.toString());
            if (properties.channel() != null && !properties.channel().isBlank()) {
                payload.put("channel", properties.channel());
            }
            restClient.post()
                    .uri(parsedUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Slack notify failed cause={}", e.getMessage());
        }
    }
}
