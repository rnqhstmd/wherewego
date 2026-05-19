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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slack Incoming Webhook 알림 발송 (Block Kit).
 *
 * <p>{@code slack.webhook-uri}가 비어 있으면 no-op (dev/test 환경).
 * 모든 예외는 swallow + log.warn.</p>
 */
@Component
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    private static final int TIMEOUT_MS = 2_000;
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /** 🚨 실패 알림 — 빨간색 */
    public void notifyFailure(String title, Map<String, Object> context) {
        send("🚨", title, context, "#FF0000");
    }

    /** ⚠️ 경고 알림 — 노란색 */
    public void notifyWarning(String title, Map<String, Object> context) {
        send("⚠️", title, context, "#FFA500");
    }

    /** ✅ 정보/성공 알림 — 초록색 */
    public void notify(String title, Map<String, Object> context) {
        send("✅", title, context, "#36a64f");
    }

    private void send(String emoji, String title, Map<String, Object> context, String color) {
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
            String appName = properties.username() == null ? "wherewego" : properties.username();
            List<Map<String, Object>> blocks = new ArrayList<>();

            // 제목
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("type", "section");
            Map<String, Object> headerText = new LinkedHashMap<>();
            headerText.put("type", "mrkdwn");
            headerText.put("text", emoji + " *[" + appName + "] " + title + "*");
            header.put("text", headerText);
            blocks.add(header);

            // 컨텍스트 필드 (2열 그리드)
            if (context != null && !context.isEmpty()) {
                Map<String, Object> fieldsSection = new LinkedHashMap<>();
                fieldsSection.put("type", "section");
                List<Map<String, Object>> fields = new ArrayList<>();
                for (Map.Entry<String, Object> e : context.entrySet()) {
                    Map<String, Object> field = new LinkedHashMap<>();
                    field.put("type", "mrkdwn");
                    field.put("text", "*" + e.getKey() + "*\n" + e.getValue());
                    fields.add(field);
                }
                fieldsSection.put("fields", fields);
                blocks.add(fieldsSection);
            }

            // 타임스탬프
            String timestamp = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(TIMESTAMP_FMT);
            Map<String, Object> ctxBlock = new LinkedHashMap<>();
            ctxBlock.put("type", "context");
            ctxBlock.put("elements", List.of(Map.of("type", "mrkdwn", "text", "📅 " + timestamp + " KST")));
            blocks.add(ctxBlock);

            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("color", color);
            attachment.put("blocks", blocks);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("attachments", List.of(attachment));
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
