package com.wherewego.domain.chatbot;

import com.wherewego.config.cache.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 인스타 링크 받은 직후 메모 입력을 기다리는 pending 세션.
 * <p>key = botUserKey, value = instagramUrl, TTL = 10분.</p>
 *
 * <p>흐름:
 * <ol>
 *   <li>사용자가 인스타 URL 전송 → {@link #put}으로 저장 + "메모 보내주세요" 안내</li>
 *   <li>사용자가 다음 메시지(메모/저장/취소/새 URL) 전송 → {@link #peek}으로 pending URL 꺼냄</li>
 *   <li>처리 후 {@link #invalidate}로 세션 해제</li>
 * </ol></p>
 */
@Component
public class PendingInstagramSession {

    private final CacheManager cacheManager;

    public PendingInstagramSession(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void put(String botUserKey, String instagramUrl) {
        cache().put(botUserKey, instagramUrl);
    }

    public Optional<String> peek(String botUserKey) {
        Cache.ValueWrapper wrapper = cache().get(botUserKey);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        if (value instanceof String url) {
            return Optional.of(url);
        }
        return Optional.empty();
    }

    public void invalidate(String botUserKey) {
        cache().evict(botUserKey);
    }

    private Cache cache() {
        Cache cache = cacheManager.getCache(CacheConfig.INSTAGRAM_PENDING);
        if (cache == null) {
            throw new IllegalStateException("Cache not configured: " + CacheConfig.INSTAGRAM_PENDING);
        }
        return cache;
    }
}
