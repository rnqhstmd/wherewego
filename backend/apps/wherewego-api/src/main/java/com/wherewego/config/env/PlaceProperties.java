package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "place")
public record PlaceProperties(
        @Valid Instagram instagram,
        @Valid Search search,
        @Valid Scraper scraper
) {
    public record Instagram(
            /**
             * 인스타그램 스크래핑 활성화 여부.
             * TODO(Phase 5): @RefreshScope + Spring Cloud Config 도입 시 즉시 토글 가능.
             * 현재는 재기동 필요. 법무 미승인 환경에서는 false로 운영.
             */
            boolean scrapingEnabled
    ) { }

    public record Search(
            @Positive long syncDeadlineMs,
            @Positive int kakaoLocalSize,
            @Positive long googleSyncThresholdMs
    ) { }

    public record Scraper(
            @Valid InstagramScraper instagram,
            @Valid Gemini gemini
    ) { }

    public record InstagramScraper(
            @Positive int timeoutMs
    ) { }

    public record Gemini(
            boolean enabled,
            @NotBlank String apiKey,
            @Positive int timeoutMs,
            @Positive int dailyQuotaPerUser
    ) { }
}
