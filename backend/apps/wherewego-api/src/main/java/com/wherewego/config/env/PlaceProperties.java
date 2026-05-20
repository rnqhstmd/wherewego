package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.validation.annotation.Validated;

/**
 * 장소 도메인 관련 설정.
 *
 * <p>Phase 2.6 PR-B 완료. {@code POST /actuator/refresh} 호출로 즉시 갱신 가능.
 * 사용 측은 sub-property 캡처 금지 — 매 호출 시점에
 * {@code placeProperties.scraper().gemini().enabled()} 형태로 평가할 것.</p>
 *
 * <p>구현 노트: {@link RefreshScope}는 CGLIB 프록시를 생성하므로 record(final)와 호환되지 않는다.
 * 모든 nested type을 일반 class + final 필드 + fluent 메서드명으로 유지해 record 의미를 보존하면서
 * 프록시 생성을 허용한다. 호출 측은 메서드 시그니처가 동일하므로 변경 불필요.</p>
 */
@RefreshScope
@Validated
@ConfigurationProperties(prefix = "place")
public class PlaceProperties {

    private final Instagram instagram;
    private final Search search;
    private final Scraper scraper;

    public PlaceProperties(
            @Valid Instagram instagram,
            @Valid Search search,
            @Valid Scraper scraper
    ) {
        this.instagram = instagram;
        this.search = search;
        this.scraper = scraper;
    }

    public Instagram instagram() {
        return instagram;
    }

    public Search search() {
        return search;
    }

    public Scraper scraper() {
        return scraper;
    }

    public static class Instagram {
        /**
         * 인스타그램 스크래핑 활성화 여부.
         * 법무 미승인 환경에서는 false로 운영. {@code POST /actuator/refresh}로 즉시 토글 가능.
         */
        private final boolean scrapingEnabled;

        public Instagram(boolean scrapingEnabled) {
            this.scrapingEnabled = scrapingEnabled;
        }

        public boolean scrapingEnabled() {
            return scrapingEnabled;
        }
    }

    public static class Search {
        @Positive
        private final long syncDeadlineMs;
        @Positive
        private final int kakaoLocalSize;
        @Positive
        private final long googleSyncThresholdMs;

        public Search(
                @Positive long syncDeadlineMs,
                @Positive int kakaoLocalSize,
                @Positive long googleSyncThresholdMs
        ) {
            this.syncDeadlineMs = syncDeadlineMs;
            this.kakaoLocalSize = kakaoLocalSize;
            this.googleSyncThresholdMs = googleSyncThresholdMs;
        }

        public long syncDeadlineMs() {
            return syncDeadlineMs;
        }

        public int kakaoLocalSize() {
            return kakaoLocalSize;
        }

        public long googleSyncThresholdMs() {
            return googleSyncThresholdMs;
        }
    }

    public static class Scraper {
        private final InstagramScraper instagram;
        private final Gemini gemini;

        public Scraper(
                @Valid InstagramScraper instagram,
                @Valid Gemini gemini
        ) {
            this.instagram = instagram;
            this.gemini = gemini;
        }

        public InstagramScraper instagram() {
            return instagram;
        }

        public Gemini gemini() {
            return gemini;
        }
    }

    public static class InstagramScraper {
        @Positive
        private final int timeoutMs;

        public InstagramScraper(@Positive int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int timeoutMs() {
            return timeoutMs;
        }
    }

    public static class Gemini {
        private final boolean enabled;
        private final String apiKey;
        @NotBlank
        private final String baseUrl;
        @Positive
        private final int timeoutMs;
        @Positive
        private final int dailyQuotaPerUser;

        public Gemini(
                boolean enabled,
                String apiKey,
                @NotBlank String baseUrl,
                @Positive int timeoutMs,
                @Positive int dailyQuotaPerUser
        ) {
            this.enabled = enabled;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.timeoutMs = timeoutMs;
            this.dailyQuotaPerUser = dailyQuotaPerUser;
        }

        public boolean enabled() {
            return enabled;
        }

        public String apiKey() {
            return apiKey;
        }

        public String baseUrl() {
            return baseUrl;
        }

        public int timeoutMs() {
            return timeoutMs;
        }

        public int dailyQuotaPerUser() {
            return dailyQuotaPerUser;
        }

        @AssertTrue(message = "place.scraper.gemini.api-key는 enabled=true일 때 필수입니다")
        public boolean isApiKeyValidWhenEnabled() {
            return !enabled || (apiKey != null && !apiKey.isBlank());
        }
    }
}
