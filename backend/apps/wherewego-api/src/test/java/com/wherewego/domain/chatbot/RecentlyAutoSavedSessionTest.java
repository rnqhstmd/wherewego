package com.wherewego.domain.chatbot;

import com.wherewego.config.cache.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecentlyAutoSavedSession} 단위 동작 검증.
 *
 * <p>풀 SpringBootTest로 띄우는 이유: Caffeine cache TTL 설정 + CacheConfig 합성 키 동작을
 * 실제 빈으로 검증한다. 외부 의존(DB/카카오)이 없는 단순 캐시 세션이므로 비용 작음.</p>
 */
@SpringBootTest(classes = {CacheConfig.class, RecentlyAutoSavedSession.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "chatbot.instagram.pending-ttl-seconds=60",
        "chatbot.instagram.recently-saved-ttl-seconds=60"
})
class RecentlyAutoSavedSessionTest {

    @Autowired
    private RecentlyAutoSavedSession session;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache(CacheConfig.INSTAGRAM_RECENTLY_SAVED).clear();
    }

    @Test
    @DisplayName("put → peek 동일 URL 일치")
    void putThenPeek_sameUrl_returnsRecord() {
        session.put("bot-1", "https://insta.com/p/abc", "body-1");

        Optional<RecentlyAutoSaved> peeked = session.peek("bot-1", "https://insta.com/p/abc");

        assertThat(peeked).isPresent();
        assertThat(peeked.get().url()).isEqualTo("https://insta.com/p/abc");
        assertThat(peeked.get().responseBody()).isEqualTo("body-1");
        assertThat(peeked.get().savedAt()).isNotNull();
    }

    @Test
    @DisplayName("peek 다른 URL은 empty — 합성 키로 URL 단위 독립 보관")
    void peek_differentUrl_returnsEmpty() {
        session.put("bot-1", "https://insta.com/p/abc", "body-A");
        session.put("bot-1", "https://insta.com/p/xyz", "body-B");

        assertThat(session.peek("bot-1", "https://insta.com/p/abc"))
                .map(RecentlyAutoSaved::responseBody).contains("body-A");
        assertThat(session.peek("bot-1", "https://insta.com/p/xyz"))
                .map(RecentlyAutoSaved::responseBody).contains("body-B");
        assertThat(session.peek("bot-1", "https://insta.com/p/none")).isEmpty();
    }

    @Test
    @DisplayName("peek 다른 botUserKey는 empty")
    void peek_differentBotUserKey_returnsEmpty() {
        session.put("bot-1", "https://insta.com/p/abc", "body");

        assertThat(session.peek("bot-2", "https://insta.com/p/abc")).isEmpty();
    }

    @Test
    @DisplayName("invalidate 후 peek empty")
    void invalidate_thenPeekEmpty() {
        session.put("bot-1", "https://insta.com/p/abc", "body");
        session.invalidate("bot-1", "https://insta.com/p/abc");

        assertThat(session.peek("bot-1", "https://insta.com/p/abc")).isEmpty();
    }

    @Test
    @DisplayName("같은 botUserKey + 같은 url 재put은 덮어쓰기")
    void putTwice_sameKey_overwrites() {
        session.put("bot-1", "https://insta.com/p/abc", "body-v1");
        session.put("bot-1", "https://insta.com/p/abc", "body-v2");

        assertThat(session.peek("bot-1", "https://insta.com/p/abc"))
                .map(RecentlyAutoSaved::responseBody).contains("body-v2");
    }

    @Test
    @DisplayName("빈 body도 보관 — RESEND-1 가드는 빈 body여도 발동")
    void emptyBody_isStored() {
        session.put("bot-1", "https://insta.com/p/abc", "");

        Optional<RecentlyAutoSaved> peeked = session.peek("bot-1", "https://insta.com/p/abc");
        assertThat(peeked).isPresent();
        assertThat(peeked.get().responseBody()).isEmpty();
    }
}
