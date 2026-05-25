package com.wherewego.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String TWO_SECOND_MEMO = "twoSecondMemo";
    public static final String INSTAGRAM_PENDING = "instagramPending";
    public static final String INSTAGRAM_PENDING_NOTIFICATION = "instagramPendingNotification";
    public static final String INSTAGRAM_RECENTLY_SAVED = "instagramRecentlySaved";
    public static final String PLACE_SELECTION_CANDIDATE = "placeSelectionCandidate";
    public static final String GEMINI_USER_QUOTA = "geminiUserQuota";
    public static final String GEMINI_RESPONSE_CACHE = "geminiResponseCache";
    public static final String GOOGLE_PLACES_RESPONSE_CACHE = "googlePlacesResponseCache";
    public static final String ONBOARDING_STATUS = "onboardingStatus";

    @Bean
    public CacheManager cacheManager(
            @Value("${chatbot.instagram.pending-ttl-seconds:60}") long instagramPendingTtlSeconds,
            @Value("${chatbot.instagram.recently-saved-ttl-seconds:600}") long instagramRecentlySavedTtlSeconds) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(TWO_SECOND_MEMO,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(2))
                        .maximumSize(10_000)
                        .build());
        manager.registerCustomCache(INSTAGRAM_PENDING,
                Caffeine.newBuilder()
                        // cache TTL은 scheduler delay 보다 충분히 길어야 한다.
                        // 자동 저장 trigger 시점에 peek 가드가 cache evict 와 user invalidate 를 구분하기 위함.
                        // user invalidate(메모/저장 발화) 만이 명시적 empty 신호이며, cache evict 는 안전망.
                        .expireAfterWrite(Duration.ofSeconds(instagramPendingTtlSeconds * 5L))
                        .maximumSize(10_000)
                        .build());
        manager.registerCustomCache(INSTAGRAM_PENDING_NOTIFICATION,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofDays(7))
                        .maximumSize(10_000)
                        .build());
        // RESEND-1 가드용. key = botUserKey + "|" + sha256Hex(url), value = RecentlyAutoSaved record.
        // 자동/메모 흐름으로 저장 완료된 URL을 짧은 윈도우 안에 재전송하면 "이미 저장됨" 안내.
        manager.registerCustomCache(INSTAGRAM_RECENTLY_SAVED,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(instagramRecentlySavedTtlSeconds))
                        .maximumSize(2_000)
                        .build());
        manager.registerCustomCache(PLACE_SELECTION_CANDIDATE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .maximumSize(10_000)
                        .build());
        manager.registerCustomCache(GEMINI_USER_QUOTA,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofHours(24))
                        .maximumSize(10_000)
                        .build());
        manager.registerCustomCache(GEMINI_RESPONSE_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofHours(24))
                        .maximumSize(2_000)
                        .build());
        manager.registerCustomCache(GOOGLE_PLACES_RESPONSE_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofHours(24))
                        .maximumSize(1_000)
                        .build());
        // Phase 11 PR-B: 온보딩 진입 상태 (활성 그룹 / 멤버 수 / 봇 매핑) 사용자별 60초 캐시.
        manager.registerCustomCache(ONBOARDING_STATUS,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(60))
                        .maximumSize(10_000)
                        .build());
        return manager;
    }
}
