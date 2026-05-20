package com.wherewego.infrastructure.scraper.instagram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InstagramBlockedRateTracker} 상태 누적/스왑/리셋/동시성 검증.
 */
@DisplayName("InstagramBlockedRateTracker 는,")
class InstagramBlockedRateTrackerTest {

    @DisplayName("recordAttempt 만 N 회 호출하면 flushWindow.attempts == N, blocked == 0 이다.")
    @Test
    void recordAttempt_onlyAttempts_blockedZero() {
        // arrange
        InstagramBlockedRateTracker tracker = new InstagramBlockedRateTracker();

        // act
        for (int i = 0; i < 7; i++) {
            tracker.recordAttempt();
        }
        InstagramBlockedRateTracker.Snapshot snap = tracker.flushWindow();

        // assert
        assertThat(snap.attempts()).isEqualTo(7L);
        assertThat(snap.blocked()).isEqualTo(0L);
        assertThat(snap.lastBlockedUrl()).isNull();
    }

    @DisplayName("recordAttempt + recordBlocked 호출하면 attempts/blocked/lastBlockedUrl 모두 캡처된다.")
    @Test
    void recordAttempt_andRecordBlocked_capturesAll() {
        // arrange
        InstagramBlockedRateTracker tracker = new InstagramBlockedRateTracker();

        // act
        tracker.recordAttempt();
        tracker.recordAttempt();
        tracker.recordAttempt();
        tracker.recordBlocked("https://instagram.com/p/abc");
        tracker.recordBlocked("https://instagram.com/p/def");
        InstagramBlockedRateTracker.Snapshot snap = tracker.flushWindow();

        // assert
        assertThat(snap.attempts()).isEqualTo(3L);
        assertThat(snap.blocked()).isEqualTo(2L);
        assertThat(snap.lastBlockedUrl()).isEqualTo("https://instagram.com/p/def");
    }

    @DisplayName("(AC-14) flushWindow 호출 후 즉시 재 flushWindow 시 모든 카운터/URL 이 0/null 로 리셋된다.")
    @Test
    void flushWindow_resetsAfterCapture() {
        // arrange
        InstagramBlockedRateTracker tracker = new InstagramBlockedRateTracker();
        tracker.recordAttempt();
        tracker.recordBlocked("https://x");

        // act
        InstagramBlockedRateTracker.Snapshot first = tracker.flushWindow();
        InstagramBlockedRateTracker.Snapshot second = tracker.flushWindow();

        // assert
        assertThat(first.attempts()).isEqualTo(1L);
        assertThat(first.blocked()).isEqualTo(1L);
        assertThat(second.attempts()).isEqualTo(0L);
        assertThat(second.blocked()).isEqualTo(0L);
        assertThat(second.lastBlockedUrl()).isNull();
    }

    @DisplayName("recordBlocked 에 CRLF 가 포함된 URL 을 전달하면 lastBlockedUrl 에서 CRLF 가 무력화된다.")
    @Test
    void recordBlocked_crlfInUrl_neutralizedInSnapshot() {
        // arrange
        InstagramBlockedRateTracker tracker = new InstagramBlockedRateTracker();

        // act
        tracker.recordBlocked("http://evil\r\nINJECTED");
        InstagramBlockedRateTracker.Snapshot snap = tracker.flushWindow();

        // assert
        assertThat(snap.lastBlockedUrl())
                .doesNotContain("\r")
                .doesNotContain("\n")
                .contains("INJECTED");
    }

    @DisplayName("10 thread × 100 회 동시 recordAttempt 호출 시 flushWindow.attempts == 1000 으로 정합한다 (synchronized 검증).")
    @Test
    void recordAttempt_concurrent_consistentTotal() throws InterruptedException {
        // arrange
        InstagramBlockedRateTracker tracker = new InstagramBlockedRateTracker();
        int threads = 10;
        int perThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        // act
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        tracker.recordAttempt();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        // assert
        assertThat(finished).isTrue();
        InstagramBlockedRateTracker.Snapshot snap = tracker.flushWindow();
        assertThat(snap.attempts()).isEqualTo((long) threads * perThread);
    }
}
