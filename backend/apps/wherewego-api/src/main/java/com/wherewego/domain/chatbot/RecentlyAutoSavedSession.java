package com.wherewego.domain.chatbot;

import com.wherewego.config.cache.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 직전 자동/메모 흐름으로 저장된 인스타 URL을 짧은 윈도우 동안 보관하는 세션.
 *
 * <p>같은 사용자가 동일 URL을 재전송했을 때 RESEND-1 안내를 띄우기 위한 가드용.
 * key = {@code botUserKey + "|" + sha256Hex(url)} 합성 키로 같은 사용자가
 * 여러 URL을 자동 저장해도 각각 독립 보관된다.</p>
 *
 * <p>TTL = {@code chatbot.instagram.recently-saved-ttl-seconds} (기본 600초).
 * 적재 경로:
 * <ul>
 *   <li>{@code processWithMemoAsync} — callback async push 직전 + 동기 fallback 직후</li>
 *   <li>{@code autoSaveOnExpiry} — TTL 만료 자동 저장</li>
 *   <li>{@code autoSavePreviousImmediately} — D 시나리오 즉시 저장</li>
 * </ul></p>
 */
@Component
public class RecentlyAutoSavedSession {

    private final CacheManager cacheManager;

    public RecentlyAutoSavedSession(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void put(String botUserKey, String url, String responseBody) {
        cache().put(key(botUserKey, url),
                new RecentlyAutoSaved(url, responseBody, Instant.now()));
    }

    public Optional<RecentlyAutoSaved> peek(String botUserKey, String url) {
        Cache.ValueWrapper wrapper = cache().get(key(botUserKey, url));
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        if (value instanceof RecentlyAutoSaved recent) {
            return Optional.of(recent);
        }
        return Optional.empty();
    }

    public void invalidate(String botUserKey, String url) {
        cache().evict(key(botUserKey, url));
    }

    private static String key(String botUserKey, String url) {
        return botUserKey + "|" + sha256Hex(url);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Cache cache() {
        Cache cache = cacheManager.getCache(CacheConfig.INSTAGRAM_RECENTLY_SAVED);
        if (cache == null) {
            throw new IllegalStateException(
                    "Cache not configured: " + CacheConfig.INSTAGRAM_RECENTLY_SAVED);
        }
        return cache;
    }
}
