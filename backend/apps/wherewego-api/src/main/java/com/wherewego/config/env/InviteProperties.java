package com.wherewego.config.env;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 초대 링크 설정 (Phase 11 PR-A).
 *
 * @param ttl           초대 링크 유효 기간 (기본 P7D = 7일)
 * @param shareBaseUrl  공유 URL 베이스 (예: https://wherewego.app). slug 와 결합되어 shareUrl 생성
 * @param rateLimit     by-slug(GET) + accept(POST) 초대 링크 경로의 IP 기반 레이트리밋 (IP 예산 공유)
 */
@Validated
@ConfigurationProperties(prefix = "app.invite")
public record InviteProperties(
        @DefaultValue("P7D") Duration ttl,
        @DefaultValue("https://wherewego.win") String shareBaseUrl,
        @DefaultValue RateLimit rateLimit
) {
    public record RateLimit(
            @Positive @DefaultValue("30") int capacity,
            @Positive @DefaultValue("60") long refillSeconds,
            @Positive @DefaultValue("10000") int maxKeys
    ) { }
}
