package com.wherewego.domain.chatbot;

import com.wherewego.config.cache.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 자동 저장 결과를 사용자 다음 발화 시점에 prepend하기 위한 세션.
 *
 * <p>Phase 12 {@code ReelSelectionAutoSaveScheduler} 가 TTL 만료 자동 저장 결과 텍스트를 본 세션에
 * 적재한다. 사용자가 다음에 챗봇에 무언가 보내면 {@code ChatbotWebhookService.decorate}가 응답 본문
 * 앞에 1회 prepend 후 invalidate.</p>
 *
 * <p>key = botUserKey, value = 알림 텍스트, TTL = 7일.</p>
 *
 * <p>참고: {@link RecentlyAutoSavedSession}과 별도. 본 세션은 "사용자 다음 발화 시 1회 prepend"
 * 의미 + 7일 TTL인 반면, {@link RecentlyAutoSavedSession}은 "URL 단위 RESEND-1 가드" 의미 +
 * 10분 TTL이라 의미·TTL이 달라 통합하지 않는다.</p>
 */
@Component
public class PendingNotificationSession {

    private final CacheManager cacheManager;

    public PendingNotificationSession(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void put(String botUserKey, String noticeText) {
        cache().put(botUserKey, noticeText);
    }

    public Optional<String> peek(String botUserKey) {
        Cache.ValueWrapper wrapper = cache().get(botUserKey);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        if (value instanceof String text) {
            return Optional.of(text);
        }
        return Optional.empty();
    }

    public void invalidate(String botUserKey) {
        cache().evict(botUserKey);
    }

    private Cache cache() {
        Cache cache = cacheManager.getCache(CacheConfig.INSTAGRAM_PENDING_NOTIFICATION);
        if (cache == null) {
            throw new IllegalStateException("Cache not configured: " + CacheConfig.INSTAGRAM_PENDING_NOTIFICATION);
        }
        return cache;
    }
}
