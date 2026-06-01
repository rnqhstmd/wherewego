package com.wherewego.config.env;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * P1: Apple identityToken 검증 설정 (FR-9).
 * audience 는 단수(앱 번들 ID) — 공격 표면 최소화(설계 결정). 다중 필요 시 확장.
 */
@Validated
@ConfigurationProperties(prefix = "apple")
public record AppleAuthProperties(
        @NotBlank String audience,
        @NotBlank String issuer,
        @NotBlank String jwksUrl,
        @Positive long jwksTtlSeconds
) { }
