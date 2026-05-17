package com.wherewego.config.env;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "slack")
public record SlackProperties(
        String username,
        String channel,
        String webhookUri  // 빈 값 허용 (no-op 정책) — 별도 검증 어노테이션 없음
) { }
