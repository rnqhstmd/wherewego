package com.wherewego.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
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
    public static final String PLACE_SELECTION_CANDIDATE = "placeSelectionCandidate";
    public static final String GEMINI_USER_QUOTA = "geminiUserQuota";
    public static final String GEMINI_RESPONSE_CACHE = "geminiResponseCache";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(TWO_SECOND_MEMO,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(2))
                        .maximumSize(10_000)
                        .build());
        manager.registerCustomCache(INSTAGRAM_PENDING,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .maximumSize(10_000)
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
        return manager;
    }
}
