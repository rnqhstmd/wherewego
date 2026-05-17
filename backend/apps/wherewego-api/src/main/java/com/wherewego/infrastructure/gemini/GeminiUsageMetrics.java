package com.wherewego.infrastructure.gemini;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Gemini API 호출 메트릭.
 *
 * <p>Spring Boot Actuator의 {@link MeterRegistry}를 통해 호출 outcome 카운터와 호출 소요시간 타이머를 발급한다.</p>
 *
 * <p>outcome 태그 값:
 * <ul>
 *     <li>{@code success} — 장소명 추출 성공</li>
 *     <li>{@code empty} — Gemini "null" 응답</li>
 *     <li>{@code cached} — 응답 캐시 히트</li>
 *     <li>{@code disabled} — feature flag off</li>
 *     <li>{@code quota_exceeded} — 사용자 일일 한도 초과</li>
 *     <li>{@code rate_limited} — 429 응답</li>
 *     <li>{@code timeout} — 호출 타임아웃</li>
 *     <li>{@code error} — 기타 오류</li>
 * </ul>
 * </p>
 */
@Component
public class GeminiUsageMetrics {

    private final MeterRegistry registry;

    public GeminiUsageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCall(String outcome) {
        Counter.builder("gemini.calls.total")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordDuration(long durationMs, String outcome) {
        registry.timer("gemini.call.duration", "outcome", outcome)
                .record(Duration.ofMillis(durationMs));
    }
}
