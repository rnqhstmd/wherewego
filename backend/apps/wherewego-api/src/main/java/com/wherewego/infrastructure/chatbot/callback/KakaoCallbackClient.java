package com.wherewego.infrastructure.chatbot.callback;

import com.wherewego.config.env.KakaoApiProperties;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;

/**
 * 카카오 i 오픈빌더 callbackUrl로 SkillResponse 푸시.
 *
 * <p>{@code useCallback: true} 응답을 보낸 후 비동기 처리 완료 시점에 호출된다.
 * 카카오는 callback을 5초 안에 1회만 받으므로 <b>재시도하지 않는다.</b>
 * 실패 시 caller (오케스트레이터) 가 Slack 알림으로 보완한다.</p>
 *
 * <p>보안: callbackUrl은 사용자별로 발급되는 일회성 URL이므로
 * 로그·예외 메시지에 포함하지 않는다 (debug 레벨에만 마스킹된 형태로 노출).</p>
 */
@Component
public class KakaoCallbackClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoCallbackClient.class);

    private final RestClient restClient;
    private final boolean strictHostCheck;

    @Autowired
    public KakaoCallbackClient(KakaoApiProperties properties) {
        this(properties.callback().timeoutMs(), true);
    }

    /**
     * 테스트 전용 생성자. {@code strictHostCheck=false}로 SSRF 가드를 우회한다.
     * WireMock 의 baseUrl(http://localhost:포트) 통합 검증에 한해 사용한다.
     */
    KakaoCallbackClient(int timeoutMs, boolean strictHostCheck) {
        this.restClient = RestClient.builder()
                .requestFactory(buildRequestFactory(timeoutMs))
                .build();
        this.strictHostCheck = strictHostCheck;
    }

    private static ClientHttpRequestFactory buildRequestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    /**
     * callbackUrl로 SkillResponse JSON POST. 실패 시 예외를 던지지 않고 swallow + warn 로그.
     * 호출자는 메서드 반환 후에도 자체 후처리 (Slack 알림 등)를 수행할 책임이 있다.
     */
    public void push(String callbackUrl, ChatbotV1Dto.SkillResponse payload) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.warn("kakao callback push skipped: callbackUrl is blank");
            return;
        }
        if (strictHostCheck && !isAllowedCallbackUrl(callbackUrl)) {
            log.warn("kakao callback push skipped: disallowed url scheme or host");
            return;
        }
        try {
            restClient.post()
                    .uri(URI.create(callbackUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("kakao callback push ok url={}", callbackUrl);
        } catch (RestClientException e) {
            log.warn("kakao callback push failed cause={}", e.getMessage());
        }
    }

    /**
     * 간단 텍스트 헬퍼.
     */
    public void pushText(String callbackUrl, String text) {
        push(callbackUrl, ChatbotV1Dto.SkillResponse.simple(text));
    }

    /**
     * 카카오 콜백 URL 최소 SSRF 가드.
     * - https 스킴만 허용 (PoC 단계에서는 정식 화이트리스트 부재; 카카오는 https로 전송 가정).
     * - 사설/로컬/링크로컬/메타데이터 IP 거부: 127.x, 10.x, 172.16~31.x, 192.168.x, 169.254.x, localhost.
     * - 호스트 누락/포맷 에러도 거부.
     */
    static boolean isAllowedCallbackUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;
            String h = host.toLowerCase();
            if (h.equals("localhost")) return false;
            // IPv4 사설 대역 차단 (도메인은 통과 — DNS 리바인딩은 본 PR 범위 외).
            if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return false;
            if (h.startsWith("172.")) {
                String[] parts = h.split("\\.");
                if (parts.length >= 2) {
                    try {
                        int second = Integer.parseInt(parts[1]);
                        if (second >= 16 && second <= 31) return false;
                    } catch (NumberFormatException ignored) { /* not numeric — domain pass */ }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
