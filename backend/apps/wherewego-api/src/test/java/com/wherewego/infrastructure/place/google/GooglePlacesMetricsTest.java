package com.wherewego.infrastructure.place.google;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GooglePlacesMetrics} 단위 검증 — SimpleMeterRegistry 주입.
 *
 * <p>outcome 라벨별 Counter/Timer 발급과 cached outcome 카운터를 검증한다.</p>
 */
@DisplayName("GooglePlacesMetrics 는 outcome 라벨 Counter/Timer 를 발급할 때,")
class GooglePlacesMetricsTest {

    private MeterRegistry meterRegistry;
    private GooglePlacesMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new GooglePlacesMetrics(meterRegistry);
    }

    @DisplayName("recordCall(\"success\") 1회 호출하면 google_places.calls.total{outcome=success} 카운터가 1 증가한다.")
    @Test
    void recordCall_success_incrementsCounter() {
        // act
        metrics.recordCall("success");

        // assert
        double count = meterRegistry.find("google_places.calls.total")
                .tag("outcome", "success")
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0);
    }

    @DisplayName("recordDuration(123, \"success\") 1회 호출하면 google_places.call.duration{outcome=success} Timer 가 123ms 를 기록한다.")
    @Test
    void recordDuration_success_recordsTimer() {
        // act
        metrics.recordDuration(123L, "success");

        // assert
        long totalMs = (long) meterRegistry.find("google_places.call.duration")
                .tag("outcome", "success")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(totalMs).isEqualTo(123L);
    }

    @DisplayName("recordCall(\"cached\") 2회 호출하면 outcome=cached 카운터가 2 가 된다.")
    @Test
    void recordCall_cached_incrementsCachedCounter() {
        // act
        metrics.recordCall("cached");
        metrics.recordCall("cached");

        // assert
        double count = meterRegistry.find("google_places.calls.total")
                .tag("outcome", "cached")
                .counter()
                .count();
        assertThat(count).isEqualTo(2.0);
    }

    @DisplayName("서로 다른 outcome 두 개를 발급하면 라벨별 Counter 가 독립적으로 누적된다.")
    @Test
    void recordCall_differentOutcomes_independentCounters() {
        // act
        metrics.recordCall("success");
        metrics.recordCall("success");
        metrics.recordCall("rate_limited");

        // assert
        double success = meterRegistry.find("google_places.calls.total")
                .tag("outcome", "success")
                .counter()
                .count();
        double rateLimited = meterRegistry.find("google_places.calls.total")
                .tag("outcome", "rate_limited")
                .counter()
                .count();
        assertThat(success).isEqualTo(2.0);
        assertThat(rateLimited).isEqualTo(1.0);
    }
}
