package com.wherewego.domain.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SSE 연결 유지를 위한 heartbeat 스케줄러.
 *
 * - 30초마다 모든 SseEmitter에 comment ":heartbeat" 발사 (FR-6).
 * - 발사 실패한 emitter는 registry 내부에서 자동 제거 (QE-2).
 * - @EnableScheduling은 RequestIdFilterConfig.java:23에 이미 활성화됨.
 */
@Component
@RequiredArgsConstructor
public class NotificationHeartbeatScheduler {

    private final NotificationSseRegistry registry;

    @Scheduled(fixedRate = 30_000L)
    public void heartbeat() {
        registry.broadcastHeartbeat();
    }
}
