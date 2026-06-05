package com.wherewego.config.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wherewego.config.env.InviteProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 초대 링크 by-slug(GET) + accept(POST) 경로에 적용하는 IP 기반 카운터.
 *
 * <p>Caffeine TTL 을 슬라이딩 윈도우로 사용한다. 키마다 카운터를 증가시키고,
 * refillSeconds 가 지나면 키가 만료되어 자동으로 리셋된다.</p>
 *
 * <p>분당 30회 정도의 경량 보호용이며, 정밀한 토큰 버킷이 필요하면 Bucket4j 로 교체한다.</p>
 */
@Component
public class InviteLinkRateLimiter {

    private final int capacity;
    private final long refillSeconds;
    private final Cache<String, AtomicInteger> cache;

    public InviteLinkRateLimiter(InviteProperties properties) {
        InviteProperties.RateLimit rl = properties.rateLimit();
        this.capacity = rl.capacity();
        this.refillSeconds = rl.refillSeconds();
        this.cache = Caffeine.newBuilder()
                .maximumSize(rl.maxKeys())
                .expireAfterWrite(Duration.ofSeconds(this.refillSeconds))
                .build();
    }

    /**
     * Retry-After 헤더 등에서 사용하기 위한 윈도우 길이(초).
     */
    public long getRefillSeconds() {
        return refillSeconds;
    }

    /**
     * 키(IP) 1회 차감. true=통과, false=레이트리밋 초과.
     */
    public boolean tryConsume(String key) {
        AtomicInteger count = Objects.requireNonNull(
                cache.get(key, k -> new AtomicInteger(0)));
        return count.incrementAndGet() <= capacity;
    }
}
