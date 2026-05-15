package com.wherewego.domain.pin.memo;

import com.wherewego.config.cache.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 2초 룰 메모 세션. Caffeine {@link CacheConfig#TWO_SECOND_MEMO} 캐시 단일 의존.
 * <p>key = botUserKey, value = pinId, TTL = 2초.</p>
 */
@Component
public class TwoSecondMemoSession {

    private final CacheManager cacheManager;

    public TwoSecondMemoSession(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void put(String botUserKey, Long pinId) {
        cache().put(botUserKey, pinId);
    }

    public Optional<Long> peek(String botUserKey) {
        Cache.ValueWrapper wrapper = cache().get(botUserKey);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        if (value instanceof Long pinId) {
            return Optional.of(pinId);
        }
        return Optional.empty();
    }

    public void invalidate(String botUserKey) {
        cache().evict(botUserKey);
    }

    private Cache cache() {
        Cache cache = cacheManager.getCache(CacheConfig.TWO_SECOND_MEMO);
        if (cache == null) {
            throw new IllegalStateException("Cache not configured: " + CacheConfig.TWO_SECOND_MEMO);
        }
        return cache;
    }
}
