package com.wherewego.infrastructure.scraper.instagram;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InstagramScraperClient} 차단 추적기 통합 검증.
 *
 * <p>tracker 호출/예외 격리/BLOCKED 분기 동작을 검증한다 (AC-11, AC-18).</p>
 */
@DisplayName("InstagramScraperClient 는,")
class InstagramScraperClientTest {

    private HtmlFetcher htmlFetcher;
    private InstagramBlockedRateTracker tracker;
    private InstagramScraperClient client;

    @BeforeEach
    void setUp() {
        htmlFetcher = mock(HtmlFetcher.class);
        tracker = mock(InstagramBlockedRateTracker.class);
        PlaceProperties.Scraper scraper = new PlaceProperties.Scraper(
                new PlaceProperties.InstagramScraper(1_000),
                new PlaceProperties.Gemini(false, "k", "http://localhost", 100, 50));
        PlaceProperties placeProperties = new PlaceProperties(
                new PlaceProperties.Instagram(true, true, 5_000L),
                new PlaceProperties.Search(4_500L, 5, 1_700L, 15_000L),
                scraper);
        client = new InstagramScraperClient(placeProperties, htmlFetcher, tracker);
    }

    private static ChatbotContext freshContext() {
        return ChatbotContext.start(5_000);
    }

    @DisplayName("(AC-18) tracker.recordAttempt 가 RuntimeException 을 던져도 fetchHtml 반환값(Optional) 은 변경되지 않는다.")
    @Test
    void trackerRecordAttemptThrows_fetchHtmlReturnUnchanged() {
        // arrange : 첫 strategy 에서 success (blocked=false)
        HtmlFetcher.FetchResult ok = new HtmlFetcher.FetchResult(
                200, "<html>og:description ...</html>", false, 10L, HtmlFetcher.Strategy.NO_UA);
        when(htmlFetcher.fetch(anyString(), eq(HtmlFetcher.Strategy.NO_UA), any(Duration.class)))
                .thenReturn(ok);
        doThrow(new RuntimeException("tracker boom")).when(tracker).recordAttempt();

        // act
        Optional<String> result = client.fetchHtml("https://instagram.com/p/abc", freshContext());

        // assert
        assertThat(result).isPresent();
        assertThat(result.get()).contains("og:description");
        verify(tracker, atLeastOnce()).recordAttempt();
    }

    @DisplayName("(AC-11) 모든 strategy 가 blocked 면 tracker.recordBlocked(url) + recordAttempt() 가 호출된다.")
    @Test
    void allStrategiesBlocked_callsRecordBlockedAndRecordAttempt() {
        // arrange : 모든 stage blocked
        HtmlFetcher.FetchResult blocked = new HtmlFetcher.FetchResult(
                403, "", true, 10L, HtmlFetcher.Strategy.NO_UA);
        when(htmlFetcher.fetch(anyString(), any(HtmlFetcher.Strategy.class), any(Duration.class)))
                .thenReturn(blocked);

        // act
        Optional<String> result = client.fetchHtml("https://instagram.com/p/blocked", freshContext());

        // assert
        assertThat(result).isEmpty();
        verify(tracker, times(1)).recordBlocked("https://instagram.com/p/blocked");
        verify(tracker, times(1)).recordAttempt();
    }

    @DisplayName("(AC-11) 데드라인 초과(TIMEOUT) 시 HtmlFetcher 미호출 + recordAttempt 만 +1, recordBlocked 호출 없음.")
    @Test
    void deadlineExpired_recordsAttemptOnly_noBlockedNoFetch() {
        // arrange : deadline=0 → 첫 strategy 진입 시점에 remaining() <= 0
        ChatbotContext expiredCtx = ChatbotContext.start(0L);

        // act
        Optional<String> result = client.fetchHtml("https://instagram.com/p/timeout", expiredCtx);

        // assert
        assertThat(result).isEmpty();
        verifyNoInteractions(htmlFetcher); // 데드라인 초과 → 외부 호출 없음
        verify(tracker, times(1)).recordAttempt(); // TIMEOUT 도 attempt 로 카운트
        verify(tracker, never()).recordBlocked(anyString()); // BLOCKED 분기 아님
    }
}
