package com.wherewego.infrastructure.gemini;

import com.wherewego.config.cache.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Gemini API 응답 캐시.
 *
 * <p>SHA-256(safeCaption) hex 키로 24h 윈도우 캐싱. 동일 캡션 재호출을 Gemini 호출 없이 응답.
 * {@link Optional#empty()}(Gemini "null" 반환)도 캐싱하여 무가치한 캡션의 재호출을 막는다.
 * rate_limit/timeout/error는 일시적 장애이므로 캐싱하지 않는다.</p>
 */
@Component
public class GeminiResponseCacheService {

    private final CacheManager cacheManager;

    public GeminiResponseCacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public String hashKey(String safeCaption) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(safeCaption.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "raw:" + Integer.toHexString(safeCaption.hashCode());
        }
    }

    /**
     * @return outer Optional = hit/miss, inner Optional = Gemini 결과 (empty = "null" 응답).
     */
    @SuppressWarnings("unchecked")
    public Optional<Optional<String>> get(String captionKey) {
        Cache cache = cacheManager.getCache(CacheConfig.GEMINI_RESPONSE_CACHE);
        if (cache == null) {
            return Optional.empty();
        }
        Cache.ValueWrapper wrapper = cache.get(captionKey);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        if (value instanceof Optional<?> opt) {
            return Optional.of((Optional<String>) opt);
        }
        return Optional.empty();
    }

    public void put(String captionKey, Optional<String> result) {
        Cache cache = cacheManager.getCache(CacheConfig.GEMINI_RESPONSE_CACHE);
        if (cache == null) {
            return;
        }
        cache.put(captionKey, result);
    }
}
