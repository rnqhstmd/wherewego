package com.wherewego.domain.chatbot;

import com.wherewego.config.security.RequestIdFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 인스타 pending 메모 입력 미응답에 대비한 자동 저장 스케줄러.
 *
 * <p>botUserKey 단위로 단일 task만 유지. 동일 키에 schedule 재호출 시 이전 task를 cancel(false) 후 새 task 등록.
 * cancel(false)이므로 이미 실행 중인 task는 중단되지 않는다 — DB unique 제약
 * ({@code uq_pins_group_instagram_place})이 동시 자동 저장의 최종 가드 역할을 한다.</p>
 *
 * <p>단일 스레드면 충분 (2인 PoC, 분당 트리거 평균 0건 수준). 예외는 task 내부에서 처리해야 하며,
 * 본 스케줄러는 swallow 후 로그만 남긴다.</p>
 */
@Component
public class PendingInstagramAutoSaveScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingInstagramAutoSaveScheduler.class);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "instagram-auto-save-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentHashMap<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    /**
     * botUserKey에 대해 delayMs 후 task 실행. 이미 같은 키에 등록된 task가 있으면 cancel(false) 후 교체.
     * delayMs=0이면 사실상 즉시 백그라운드 실행 (시나리오 D에서 사용).
     */
    public void schedule(String botUserKey, long delayMs, Runnable task) {
        ScheduledFuture<?> prev = tasks.remove(botUserKey);
        if (prev != null) {
            prev.cancel(false);
        }
        ScheduledFuture<?> next = scheduler.schedule(() -> {
            // 스케줄러 진입 시 MDC에 "SCHEDULER" 마커 주입하여 Slack 알림/로그 추적성 확보 (MUST-1).
            MDC.put(RequestIdFilter.MDC_KEY, "SCHEDULER");
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Auto-save task failed botUserKey={} cause={}", botUserKey, e.getMessage(), e);
            } finally {
                tasks.remove(botUserKey);
                MDC.clear();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        tasks.put(botUserKey, next);
    }

    /**
     * 등록된 task가 있으면 cancel(false). 없으면 no-op.
     * 사용자가 TTL 내에 응답(메모/메모 없이 저장/새 URL)을 보낸 시점에 호출한다.
     */
    public void cancel(String botUserKey) {
        ScheduledFuture<?> f = tasks.remove(botUserKey);
        if (f != null) {
            f.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
