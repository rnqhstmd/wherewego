package com.wherewego.config.env;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 카카오 챗봇 Webhook 레이트 리밋 설정 (Phase 2.6 PR-B B-3).
 *
 * @param capacity        botUserKey 1개당 허용 토큰 수 (= 윈도우 내 최대 요청 수)
 * @param refillSeconds   capacity 만큼 토큰이 일괄 충전되는 주기(초)
 * @param maxKeys         Caffeine 캐시 최대 botUserKey 개수 (메모리 가드)
 */
@Validated
@ConfigurationProperties(prefix = "chatbot.rate-limit")
public record ChatbotRateLimitProperties(
        @Positive @DefaultValue("10") int capacity,
        @Positive @DefaultValue("60") long refillSeconds,
        @Positive @DefaultValue("10000") int maxKeys
) { }
