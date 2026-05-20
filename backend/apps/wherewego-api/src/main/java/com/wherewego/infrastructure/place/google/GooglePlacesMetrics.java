package com.wherewego.infrastructure.place.google;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Places API 호출 메트릭.
 *
 * <p>Spring Boot Actuator의 {@link MeterRegistry}를 통해 호출 outcome 카운터와 호출 소요시간 타이머를 발급한다.</p>
 *
 * <p>outcome 태그 값:
 * <ul>
 *     <li>{@code success} — 장소 검색 성공</li>
 *     <li>{@code empty} — 결과 없음</li>
 *     <li>{@code cached} — 응답 캐시 히트</li>
 *     <li>{@code rate_limited} — 429 응답</li>
 *     <li>{@code timeout} — 호출 타임아웃</li>
 *     <li>{@code error} — 기타 오류</li>
 * </ul>
 * </p>
 */
@Component
public class GooglePlacesMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public GooglePlacesMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCall(String outcome) {
        counters.computeIfAbsent(outcome, o ->
                Counter.builder("google_places.calls.total")
                        .tag("outcome", o)
                        .register(registry)
        ).increment();
    }

    public void recordDuration(long durationMs, String outcome) {
        timers.computeIfAbsent(outcome, o ->
                Timer.builder("google_places.call.duration")
                        .tag("outcome", o)
                        .register(registry)
        ).record(Duration.ofMillis(durationMs));
    }
}
