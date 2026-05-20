package com.wherewego.domain.chatbot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PendingInstagramAutoSaveSchedulerTest {

    private PendingInstagramAutoSaveScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PendingInstagramAutoSaveScheduler();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @DisplayName("schedule 로 등록한 task 는 delayMs 후 실행된다.")
    @Test
    void schedule_runsAfterDelay() throws InterruptedException {
        // arrange
        CountDownLatch latch = new CountDownLatch(1);

        // act
        scheduler.schedule("user-1", 50, latch::countDown);

        // assert
        assertThat(latch.await(2, TimeUnit.SECONDS))
                .as("task가 50ms 후 실행되어야 함")
                .isTrue();
    }

    @DisplayName("delayMs=0 으로 schedule 하면 task 가 즉시 백그라운드에서 실행된다 (시나리오 D).")
    @Test
    void schedule_zeroDelay_runsImmediately() throws InterruptedException {
        // arrange
        CountDownLatch latch = new CountDownLatch(1);

        // act
        scheduler.schedule("user-1", 0, latch::countDown);

        // assert
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @DisplayName("cancel 호출 시, 아직 실행되지 않은 task 는 실행되지 않는다.")
    @Test
    void cancel_preventsExecution() throws InterruptedException {
        // arrange
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.schedule("user-1", 500, counter::incrementAndGet);

        // act
        scheduler.cancel("user-1");

        // assert
        Thread.sleep(800);
        assertThat(counter.get()).isZero();
    }

    @DisplayName("같은 botUserKey 에 schedule 을 재호출하면 이전 task 는 cancel 되고 새 task 만 실행된다.")
    @Test
    void schedule_replacesPreviousTask() throws InterruptedException {
        // arrange
        AtomicInteger firstCounter = new AtomicInteger(0);
        AtomicInteger secondCounter = new AtomicInteger(0);
        scheduler.schedule("user-1", 500, firstCounter::incrementAndGet);

        // act
        scheduler.schedule("user-1", 50, secondCounter::incrementAndGet);

        // assert
        Thread.sleep(800);
        assertThat(firstCounter.get()).as("이전 task 는 cancel 됨").isZero();
        assertThat(secondCounter.get()).as("새 task 만 실행됨").isEqualTo(1);
    }

    @DisplayName("다른 botUserKey 의 task 는 서로 영향을 주지 않는다.")
    @Test
    void schedule_independentByKey() throws InterruptedException {
        // arrange
        CountDownLatch latchA = new CountDownLatch(1);
        CountDownLatch latchB = new CountDownLatch(1);
        scheduler.schedule("user-A", 50, latchA::countDown);
        scheduler.schedule("user-B", 50, latchB::countDown);

        // act + assert
        assertThat(latchA.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(latchB.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @DisplayName("등록되지 않은 키로 cancel 을 호출해도 예외가 발생하지 않는다.")
    @Test
    void cancel_unknownKey_noop() {
        // act + assert: 예외 없이 종료
        scheduler.cancel("never-scheduled");
    }

    @DisplayName("task 내부에서 예외가 던져져도 스케줄러는 살아있어 다음 task 를 실행할 수 있다.")
    @Test
    void taskException_doesNotKillScheduler() throws InterruptedException {
        // arrange
        scheduler.schedule("user-1", 50, () -> {
            throw new RuntimeException("intentional");
        });
        Thread.sleep(200);

        // act
        CountDownLatch followUp = new CountDownLatch(1);
        scheduler.schedule("user-2", 50, followUp::countDown);

        // assert
        assertThat(followUp.await(2, TimeUnit.SECONDS))
                .as("앞 task 가 throw 해도 스케줄러는 살아있어 다음 task 실행 가능")
                .isTrue();
    }
}
