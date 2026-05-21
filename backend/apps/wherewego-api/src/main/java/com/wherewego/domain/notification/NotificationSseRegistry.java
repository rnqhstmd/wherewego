package com.wherewego.domain.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 사용자별 SseEmitter 레지스트리.
 * - userId → emitter 목록 (다중 탭 지원, FR-9)
 * - 5분 타임아웃 (BR-6)
 * - onCompletion/onTimeout/onError → 자동 제거 (QE-2)
 * - emit 실패(IOException/IllegalStateException) → 제거 + completeWithError
 *
 * 동시성: ConcurrentHashMap + CopyOnWriteArrayList → 락 없이 register/push/heartbeat 안전.
 */
@Component
public class NotificationSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseRegistry.class);
    private static final long EMITTER_TIMEOUT_MS = 5 * 60 * 1000L;
    /** 사용자별 최대 동시 SSE 연결 수 (DoS 방지). 초과 시 가장 오래된 emitter 부터 complete. */
    private static final int MAX_EMITTERS_PER_USER = 10;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        CopyOnWriteArrayList<SseEmitter> list =
                emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // 사용자별 최대 emitter 수 제한 (DoS 방지). 가장 오래된 것 부터 정리.
        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter oldest = list.get(0);
            removeEmitter(userId, oldest);
            try { oldest.complete(); } catch (Exception ignore) { /* noop */ }
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        list.add(emitter);

        Runnable remove = () -> removeEmitter(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> {
            log.debug("SSE error user={} cause={}", userId, t.getMessage());
            remove.run();
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            log.debug("SSE initial connected failed user={} cause={}", userId, e.getMessage());
            remove.run();
        }
        return emitter;
    }

    public void push(Long userId, NotificationCreatedEvent event) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name("notification").data(event));
            } catch (IOException | IllegalStateException ex) {
                log.debug("SSE push failed user={} cause={}", userId, ex.getMessage());
                removeEmitter(userId, e);
                try { e.completeWithError(ex); } catch (Exception ignore) {}
            }
        }
    }

    public void broadcastHeartbeat() {
        emitters.forEach((uid, list) -> {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException ex) {
                    removeEmitter(uid, e);
                    try { e.completeWithError(ex); } catch (Exception ignore) { /* noop */ }
                }
            }
        });
    }

    /**
     * emitter 를 registry 에서 제거한다.
     * <p>
     * 접근 제어자: 테스트 가시성을 위해 package-private 로 노출.
     * 단위 테스트에서는 {@link SseEmitter#complete()} 가 HTTP handler 미연결 상태라
     * onCompletion 콜백을 발화하지 못하므로, 동일 효과 검증을 위해 직접 호출이 필요하다.
     * 운영 코드에서는 본 클래스 내부의 콜백/예외 처리 경로에서만 호출된다.
     */
    void removeEmitter(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(userId, list); // race-safe compare-and-remove
            }
        }
    }

    // 테스트 가시성 메서드
    public int activeEmitterCount(Long userId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        return list == null ? 0 : list.size();
    }

    public int totalEmitterCount() {
        return emitters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }
}
