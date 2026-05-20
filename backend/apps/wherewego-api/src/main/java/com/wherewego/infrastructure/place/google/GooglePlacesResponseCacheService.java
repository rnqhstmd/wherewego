package com.wherewego.infrastructure.place.google;

import com.wherewego.config.cache.CacheConfig;
import com.wherewego.domain.place.PlaceSearchHit;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * Google Places API 응답 캐시.
 *
 * <p>SHA-256(keyword) hex 키로 24h 윈도우 캐싱. 동일 키워드 재호출을 Google Places 호출 없이 응답.
 * 외부 변형이 캐시 내부를 오염시키지 않도록 put/get 모두 {@link List#copyOf} immutable 복사본을 사용한다.
 * rate_limit/timeout/error는 일시적 장애이므로 캐싱하지 않는다.</p>
 */
@Component
public class GooglePlacesResponseCacheService {

    private final CacheManager cacheManager;

    public GooglePlacesResponseCacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public String hashKey(String keyword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(keyword.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 Java SE 필수 알고리즘이므로 사실상 도달 불가.
            // 도달 시 32bit hashCode fallback 은 충돌 위험이 있으므로 명시적 실패.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @return outer Optional = hit/miss. 히트 시 immutable {@link List#copyOf} 복사본 반환.
     */
    @SuppressWarnings("unchecked")
    public Optional<List<PlaceSearchHit>> get(String keywordHash) {
        Cache cache = cacheManager.getCache(CacheConfig.GOOGLE_PLACES_RESPONSE_CACHE);
        if (cache == null) {
            return Optional.empty();
        }
        Cache.ValueWrapper wrapper = cache.get(keywordHash);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        if (value instanceof List<?> list) {
            return Optional.of(List.copyOf((List<PlaceSearchHit>) list));
        }
        return Optional.empty();
    }

    public void put(String keywordHash, List<PlaceSearchHit> hits) {
        if (hits == null) {
            return;
        }
        Cache cache = cacheManager.getCache(CacheConfig.GOOGLE_PLACES_RESPONSE_CACHE);
        if (cache == null) {
            return;
        }
        cache.put(keywordHash, List.copyOf(hits));
    }
}
