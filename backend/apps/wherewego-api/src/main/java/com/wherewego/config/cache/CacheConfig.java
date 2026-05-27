package com.wherewego.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
    public static final String REEL_SELECTION = "reel-selection";

    @Bean
    public CacheManager cacheManager(
            @Value("${chatbot.instagram.pending-ttl-seconds:60}") long instagramPendingTtlSeconds,
            @Value("${chatbot.instagram.recently-saved-ttl-seconds:600}") long instagramRecentlySavedTtlSeconds,
            @Value("${chatbot.reel.selection-ttl-seconds:180}") long reelSelectionTtlSeconds) {
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
        // Phase 12: 릴스 저장 선택 상태머신 세션. key = botUserKey, value = ReelSavedSelectionSession.Snapshot.
        // TTL = chatbot.reel.selection-ttl-seconds (기본 180초 = 3분). PROCESSING/SINGLE_WANT/MULTI_SELECTING/
        // BULK_SAVE/MEMO_WAITING 모든 단계의 상태를 동일 TTL 윈도우로 통일 (D-3, D-4).
        //
        // NFR-12-5: "최초 URL 전송 후 3분" 이 만료 기준. expireAfterWrite 는 매 put 마다 TTL 을
        // 갱신하므로 상태 전이 시 윈도우가 누적되어 PRD 위반 발생. 커스텀 Expiry 로 create 시점
        // 기준 TTL 을 고정하고, update/read 시점에서는 잔여 시간을 그대로 보존한다.
        final long reelSelectionTtlNanos = TimeUnit.SECONDS.toNanos(reelSelectionTtlSeconds);
        manager.registerCustomCache(REEL_SELECTION,
                Caffeine.newBuilder()
                        .expireAfter(new Expiry<Object, Object>() {
                            @Override
                            public long expireAfterCreate(Object key, Object value, long currentTime) {
                                return reelSelectionTtlNanos;
                            }
                            @Override
                            public long expireAfterUpdate(Object key, Object value, long currentTime,
                                                           long currentDuration) {
                                // 상태 전이로 put 이 반복돼도 최초 create 시점 기준 잔여 TTL 을 유지한다.
                                return currentDuration;
                            }
                            @Override
                            public long expireAfterRead(Object key, Object value, long currentTime,
                                                         long currentDuration) {
                                return currentDuration;
                            }
                        })
                        .maximumSize(1_000)
                        .build());
        return manager;
    }
}
