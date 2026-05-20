package com.wherewego.config.env;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 API 모니터링 임계값 설정.
 *
 * <p>1시간 윈도우 기준 Gemini 5xx 비율과 Instagram 차단율 임계값을 보관한다.
 * 임계 초과 시 Slack 알림 발송 + 쿨다운을 적용한다.</p>
 *
 * <p>canonical constructor 가드:
 * <ul>
 *     <li>cooldownMinutes ≥ 1 — BR-4(알림 폭주 방지) 준수. 0 설정 시 쿨다운 비활성화되어 위반.</li>
 *     <li>rate ∈ [0.0, 1.0] — 비율 임계값 도메인 범위.</li>
 * </ul>
 * </p>
 */
@ConfigurationProperties(prefix = "monitoring.threshold")
public record MonitoringThresholdProperties(Gemini gemini, Instagram instagram) {

    public record Gemini(double serverErrorRate, int cooldownMinutes) {
        public Gemini {
            if (cooldownMinutes < 1) {
                throw new IllegalArgumentException(
                        "monitoring.threshold.gemini.cooldown-minutes must be >= 1, got " + cooldownMinutes);
            }
            if (serverErrorRate < 0.0 || serverErrorRate > 1.0) {
                throw new IllegalArgumentException(
                        "monitoring.threshold.gemini.server-error-rate must be in [0.0, 1.0], got " + serverErrorRate);
            }
        }
    }

    public record Instagram(double blockedRate, int cooldownMinutes) {
        public Instagram {
            if (cooldownMinutes < 1) {
                throw new IllegalArgumentException(
                        "monitoring.threshold.instagram.cooldown-minutes must be >= 1, got " + cooldownMinutes);
            }
            if (blockedRate < 0.0 || blockedRate > 1.0) {
                throw new IllegalArgumentException(
                        "monitoring.threshold.instagram.blocked-rate must be in [0.0, 1.0], got " + blockedRate);
            }
        }
    }
}
