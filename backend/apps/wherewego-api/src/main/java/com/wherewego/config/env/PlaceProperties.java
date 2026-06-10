package com.wherewego.config.env;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
        /**
         * GC-3(FR-GC3-2): 릴스 썸네일 비동기 스크래핑 활성화 여부. {@code scrapingEnabled}(마스터)와 AND 게이트로
         * 동작한다 — 둘 중 하나라도 false면 썸네일을 채우지 않는다(thumbnailUrl 항상 null). 즉시 토글 가능.
         */
        private final boolean reelThumbnailEnabled;
        /**
         * GC-3(FR-GC3-2): 릴스 썸네일 스크래핑 데드라인(ms). 전송 트랜잭션 밖 비동기라 카카오 5초 SLA와 독립이며,
         * og:image 1회 fetch 기준으로 짧게 둔다.
         */
        @Positive
        private final long reelThumbnailDeadlineMs;

        public Instagram(boolean scrapingEnabled,
                         boolean reelThumbnailEnabled,
                         @Positive long reelThumbnailDeadlineMs) {
            this.scrapingEnabled = scrapingEnabled;
            this.reelThumbnailEnabled = reelThumbnailEnabled;
            this.reelThumbnailDeadlineMs = reelThumbnailDeadlineMs;
        }

        public boolean scrapingEnabled() {
            return scrapingEnabled;
        }

        public boolean reelThumbnailEnabled() {
            return reelThumbnailEnabled;
        }

        public long reelThumbnailDeadlineMs() {
            return reelThumbnailDeadlineMs;
        }
    }

    public static class Search {
        @Positive
        private final long syncDeadlineMs;
        @Positive
        private final int kakaoLocalSize;
        @Positive
        private final long googleSyncThresholdMs;
        /**
         * GC-1: 그룹 채팅 온디맨드 추출 API 데드라인(FR-GC1-5). 카카오 웹훅 5초 SLA({@code syncDeadlineMs})와
         * 독립 — 팝업에 "추출 중" 표시가 있어 더 길게 허용한다.
         */
        @Positive
        private final long extractDeadlineMs;

        public Search(
                @Positive long syncDeadlineMs,
                @Positive int kakaoLocalSize,
                @Positive long googleSyncThresholdMs,
                @Positive @DefaultValue("15000") long extractDeadlineMs
        ) {
            this.syncDeadlineMs = syncDeadlineMs;
            this.kakaoLocalSize = kakaoLocalSize;
            this.googleSyncThresholdMs = googleSyncThresholdMs;
            this.extractDeadlineMs = extractDeadlineMs;
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

        public long extractDeadlineMs() {
            return extractDeadlineMs;
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
