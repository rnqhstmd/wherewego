package com.wherewego.domain.place;

import com.wherewego.config.cache.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Multiple 결과 후보 카드 임시 저장소.
 * <p>key = {@code botUserKey:placeId}, value = {@link Entry}, TTL = 10분 (CacheConfig.PLACE_SELECTION_CANDIDATE).</p>
 * <p>{@link #takeAndInvalidate} 호출 1회 사용 후 즉시 evict.</p>
 */
@Component
public class PlaceSelectionCandidateStore {

    private final CacheManager cacheManager;

    public PlaceSelectionCandidateStore(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public record Entry(PlaceSearchHit hit, String instagramUrl) { }

    public void put(String botUserKey, String placeId, Entry entry) {
        cache().put(key(botUserKey, placeId), entry);
    }

    public Optional<Entry> takeAndInvalidate(String botUserKey, String placeId) {
        String k = key(botUserKey, placeId);
        Cache.ValueWrapper wrapper = cache().get(k);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        cache().evict(k);
        if (value instanceof Entry entry) {
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    private static String key(String botUserKey, String placeId) {
        return botUserKey + ":" + placeId;
    }

    private Cache cache() {
        Cache cache = cacheManager.getCache(CacheConfig.PLACE_SELECTION_CANDIDATE);
        if (cache == null) {
            throw new IllegalStateException("Cache not configured: " + CacheConfig.PLACE_SELECTION_CANDIDATE);
        }
        return cache;
    }
}
