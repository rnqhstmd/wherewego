package com.wherewego.config.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wherewego.config.env.ChatbotRateLimitProperties;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * botUserKey 별 토큰 버킷 보관소 (Phase 2.6 PR-B B-3).
 *
 * <p>Caffeine 캐시로 botUserKey 마다 {@link Bucket}을 만들어 보관한다.
 * 캐시 만료(접근 후 10분) 또는 maxKeys 초과 시 LRU 제거되어 메모리 폭증을 방지한다.</p>
 */
@Component
public class ChatbotRateLimiter {

    private final ChatbotRateLimitProperties props;
    private final Cache<String, Bucket> buckets;

    public ChatbotRateLimiter(ChatbotRateLimitProperties props) {
        this.props = props;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(props.maxKeys())
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    /**
     * botUserKey 1건 토큰 소비를 시도한다. 토큰이 있으면 true, 없으면 false.
     */
    public boolean tryConsume(String botUserKey) {
        Bucket bucket = buckets.get(botUserKey, k -> newBucket());
        return bucket.tryConsume(1);
    }

    /**
     * 테스트 hook. 통합 테스트에서 botUserKey 별 카운터를 리셋한다.
     */
    public void invalidateAll() {
        buckets.invalidateAll();
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(props.capacity())
                        .refillIntervally(props.capacity(), Duration.ofSeconds(props.refillSeconds())))
                .build();
    }
}
