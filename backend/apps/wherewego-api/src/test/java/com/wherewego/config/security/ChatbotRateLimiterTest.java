package com.wherewego.config.security;

import com.wherewego.config.env.ChatbotRateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatbotRateLimiter} 단위 테스트 (Phase 2.6 PR-B B-3).
 *
 * <p>토큰 버킷 capacity 소진/키 격리/invalidateAll 동작을 검증한다.</p>
 */
class ChatbotRateLimiterTest {

    private static final String KEY_A = "bot-user-A";
    private static final String KEY_B = "bot-user-B";

    private ChatbotRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // capacity=3, refill=60s, maxKeys=100 으로 직접 주입
        ChatbotRateLimitProperties props = new ChatbotRateLimitProperties(3, 60L, 100);
        rateLimiter = new ChatbotRateLimiter(props);
    }

    @DisplayName("tryConsume 을 호출할 때,")
    @Nested
    class TryConsume {

        @DisplayName("capacity=3 이면 3회까지 true, 4회째는 false 를 반환한다.")
        @Test
        void tryConsume_exceedsCapacity_returnsFalse() {
            // act & assert
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isFalse();
        }

        @DisplayName("다른 botUserKey 는 독립 카운터를 가지므로 각각 capacity 만큼 소비할 수 있다.")
        @Test
        void tryConsume_independentKeys() {
            // arrange : KEY_A 를 소진
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isFalse();

            // act & assert : KEY_B 는 영향받지 않는다
            assertThat(rateLimiter.tryConsume(KEY_B)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_B)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_B)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_B)).isFalse();
        }
    }

    @DisplayName("invalidateAll 을 호출할 때,")
    @Nested
    class InvalidateAll {

        @DisplayName("invalidateAll 호출 후엔 capacity 가 다시 초기화되어 재소비할 수 있다.")
        @Test
        void invalidateAll_resetsBuckets() {
            // arrange : KEY_A 소진
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isFalse();

            // act
            rateLimiter.invalidateAll();

            // assert : 다시 capacity 만큼 가능
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isTrue();
            assertThat(rateLimiter.tryConsume(KEY_A)).isFalse();
        }
    }
}
