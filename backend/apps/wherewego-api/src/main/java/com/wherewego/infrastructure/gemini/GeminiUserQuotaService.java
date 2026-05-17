package com.wherewego.infrastructure.gemini;

import com.wherewego.config.cache.CacheConfig;
import com.wherewego.config.env.PlaceProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gemini API 사용자별 일일 호출 한도 가드.
 *
 * <p>Caffeine 기반 in-memory 카운터로 {@link PlaceProperties.Gemini#dailyQuotaPerUser()} 초과 호출을 차단한다.
 * 카운터는 24시간 expireAfterWrite — 첫 호출 시점 기준 24h 윈도우.
 * 캐시 미구성/userId null 시 fail-open(true) 반환.</p>
 */
@Component
public class GeminiUserQuotaService {

    private final CacheManager cacheManager;
    private final PlaceProperties placeProperties;

    public GeminiUserQuotaService(CacheManager cacheManager, PlaceProperties placeProperties) {
        this.cacheManager = cacheManager;
        this.placeProperties = placeProperties;
    }

    /**
     * 사용자별 일일 호출 한도를 확인하고 1 증가시킨다.
     *
     * @return true = 한도 내(호출 허용), false = 한도 초과(호출 거부)
     */
    public boolean tryConsume(Long userId) {
        if (userId == null) {
            return true;
        }
        int limit = placeProperties.scraper().gemini().dailyQuotaPerUser();
        Cache cache = cacheManager.getCache(CacheConfig.GEMINI_USER_QUOTA);
        if (cache == null) {
            return true;
        }
        AtomicInteger counter = cache.get(userId, () -> new AtomicInteger(0));
        if (counter == null) {
            return true;
        }
        int next = counter.incrementAndGet();
        return next <= limit;
    }

    public int remaining(Long userId) {
        if (userId == null) {
            return Integer.MAX_VALUE;
        }
        int limit = placeProperties.scraper().gemini().dailyQuotaPerUser();
        Cache cache = cacheManager.getCache(CacheConfig.GEMINI_USER_QUOTA);
        if (cache == null) {
            return limit;
        }
        AtomicInteger counter = cache.get(userId, AtomicInteger.class);
        if (counter == null) {
            return limit;
        }
        return Math.max(0, limit - counter.get());
    }
}
