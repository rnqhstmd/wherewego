package com.wherewego.infrastructure.monitoring;

import com.wherewego.config.env.MonitoringThresholdProperties;
import com.wherewego.infrastructure.notify.slack.SlackNotifier;
import com.wherewego.infrastructure.scraper.instagram.InstagramBlockedRateTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ThresholdMonitorScheduler} 단위 검증.
 *
 * <p>Gemini 5xx 비율/Instagram 차단율 임계 초과 시 Slack 발송 + 쿨다운 + 알고리즘 정합성을 검증한다.</p>
 */
@DisplayName("ThresholdMonitorScheduler 는,")
class ThresholdMonitorSchedulerTest {

    private MeterRegistry meterRegistry;
    private InstagramBlockedRateTracker tracker;
    private SlackNotifier slackNotifier;
    private MonitoringThresholdProperties props;
    private ThresholdMonitorScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tracker = mock(InstagramBlockedRateTracker.class);
        when(tracker.flushWindow())
                .thenReturn(new InstagramBlockedRateTracker.Snapshot(0L, 0L, null));
        slackNotifier = mock(SlackNotifier.class);
        props = new MonitoringThresholdProperties(
                new MonitoringThresholdProperties.Gemini(0.10, 5),
                new MonitoringThresholdProperties.Instagram(0.50, 5));
        scheduler = new ThresholdMonitorScheduler(meterRegistry, tracker, slackNotifier, props);
    }

    @DisplayName("(AC-8) Gemini server_error/total = 12/102 ≈ 11.76% > 10% → notifyWarning 호출 + ctx 포함.")
    @Test
    void geminiServerErrorRate_exceedsThreshold_callsNotifyWarning() {
        // arrange
        meterRegistry.counter("gemini.calls.total", "outcome", "success").increment(90);
        meterRegistry.counter("gemini.calls.total", "outcome", "server_error").increment(12);

        // act
        scheduler.runMonitoringTick();

        // assert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(slackNotifier, times(1))
                .notifyWarning(eq("Gemini 5xx 비율 임계 초과"), ctxCaptor.capture());
        Map<String, Object> ctx = ctxCaptor.getValue();
        assertThat(ctx).containsEntry("serverError", 12L);
        assertThat(ctx).containsEntry("total", 102L);
        assertThat(ctx.get("ratioPct")).asString().contains("11");
    }

    @DisplayName("(AC-9) disabled outcome 50건만 있고 effectiveTotal=0 이면 알림이 발송되지 않는다.")
    @Test
    void geminiDisabledOnly_effectiveTotalZero_skipsNotify() {
        // arrange
        meterRegistry.counter("gemini.calls.total", "outcome", "disabled").increment(50);

        // act
        scheduler.runMonitoringTick();

        // assert
        verify(slackNotifier, never()).notifyWarning(anyString(), any());
    }

    @DisplayName("(AC-10) 1차 발송 후 동일 임계 초과로 즉시 2차 호출 시 쿨다운으로 알림 미발송.")
    @Test
    void geminiCooldown_secondCallSkipped() {
        // arrange : 1차 — 임계 초과
        meterRegistry.counter("gemini.calls.total", "outcome", "success").increment(90);
        meterRegistry.counter("gemini.calls.total", "outcome", "server_error").increment(12);

        // act : 1차 호출
        scheduler.runMonitoringTick();
        verify(slackNotifier, times(1)).notifyWarning(anyString(), any());

        // arrange : 2차 — 추가 server_error 발생 (이전 스냅샷 대비 델타도 임계 초과)
        reset(slackNotifier);
        meterRegistry.counter("gemini.calls.total", "outcome", "success").increment(90);
        meterRegistry.counter("gemini.calls.total", "outcome", "server_error").increment(12);

        // act : 2차 호출 — 쿨다운 5분 미경과
        scheduler.runMonitoringTick();

        // assert
        verify(slackNotifier, never()).notifyWarning(anyString(), any());
    }

    @DisplayName("(MD1) cooldownMinutes=0 이면 record canonical constructor 가드가 IllegalArgumentException 던진다 (BR-4 보호).")
    @Test
    void geminiCooldownZero_throwsByGuard() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MonitoringThresholdProperties.Gemini(0.10, 0));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MonitoringThresholdProperties.Instagram(0.50, 0));
    }

    @DisplayName("(AC-12) Instagram blocked/attempts=6/10=60% > 50% → notifyFailure 호출 + ctx 포함.")
    @Test
    void instagramBlockedRate_exceedsThreshold_callsNotifyFailure() {
        // arrange
        when(tracker.flushWindow()).thenReturn(
                new InstagramBlockedRateTracker.Snapshot(10L, 6L, "https://x.com/p/abc"));

        // act
        scheduler.runMonitoringTick();

        // assert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(slackNotifier, times(1))
                .notifyFailure(eq("Instagram 차단율 임계 초과"), ctxCaptor.capture());
        Map<String, Object> ctx = ctxCaptor.getValue();
        assertThat(ctx).containsEntry("attempts", 10L);
        assertThat(ctx).containsEntry("blocked", 6L);
        assertThat(ctx).containsEntry("lastBlockedUrl", "https://x.com/p/abc");
    }

    @DisplayName("(AC-13) Instagram attempts=0 이면 알림이 발송되지 않는다.")
    @Test
    void instagramAttemptsZero_skipsNotify() {
        // arrange : 기본 setUp 의 mock이 이미 (0, 0, null) 반환

        // act
        scheduler.runMonitoringTick();

        // assert
        verify(slackNotifier, never()).notifyFailure(anyString(), any());
    }

    @DisplayName("(MD2) Instagram 1차 발송 후 즉시 2차 호출 시 쿨다운으로 알림 미발송.")
    @Test
    void instagramCooldown_secondCallSkipped() {
        // arrange : 1차 — blocked/attempts = 6/10 = 60% > 50%
        when(tracker.flushWindow()).thenReturn(
                new InstagramBlockedRateTracker.Snapshot(10L, 6L, "https://x.com/p/abc"));

        // act : 1차 호출
        scheduler.runMonitoringTick();
        verify(slackNotifier, times(1)).notifyFailure(anyString(), any());

        // arrange : 2차 — 동일 임계 초과 스냅샷
        reset(slackNotifier);
        when(tracker.flushWindow()).thenReturn(
                new InstagramBlockedRateTracker.Snapshot(10L, 6L, "https://x.com/p/def"));

        // act : 2차 호출 — 쿨다운 5분 미경과
        scheduler.runMonitoringTick();

        // assert
        verify(slackNotifier, never()).notifyFailure(anyString(), any());
    }

    @DisplayName("(AC-17, NFR-4) MeterRegistry 가 예외를 던져도 Instagram check 는 정상 실행되고 스케줄러는 죽지 않는다.")
    @Test
    void geminiCheckThrows_instagramCheckStillRunsAndSchedulerSurvives() {
        // arrange
        MeterRegistry brokenMeter = mock(MeterRegistry.class);
        when(brokenMeter.find(anyString())).thenThrow(new RuntimeException("boom"));
        when(tracker.flushWindow())
                .thenReturn(new InstagramBlockedRateTracker.Snapshot(10L, 6L, "https://x"));
        ThresholdMonitorScheduler s = new ThresholdMonitorScheduler(
                brokenMeter, tracker, slackNotifier, props);

        // act : 첫 호출 — Gemini check 폭발, Instagram check 는 진행
        s.runMonitoringTick();

        // assert
        verify(slackNotifier, times(1)).notifyFailure(anyString(), any());

        // act : 두 번째 호출도 스케줄러가 살아있어야 한다.
        reset(slackNotifier);
        s.runMonitoringTick();
        verify(slackNotifier, times(1)).notifyFailure(anyString(), any());
    }

    @DisplayName("(MUST-ADDRESS 2) 첫 호출 시 previousSnapshot 비어있으면 server_error 첫 등장 시 delta=current 전체값으로 계산.")
    @Test
    void firstCall_lazyOutcome_deltaEqualsCurrent() {
        // arrange : 첫 호출 전 카운터가 이미 누적되어 있는 상태 (서비스가 먼저 호출되어 카운터가 쌓인 후 스케줄러가 첫 tick)
        meterRegistry.counter("gemini.calls.total", "outcome", "success").increment(50);
        meterRegistry.counter("gemini.calls.total", "outcome", "server_error").increment(20);
        // 20/70 ≈ 28.6% > 10%

        // act
        scheduler.runMonitoringTick();

        // assert : delta = current 전체값
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(slackNotifier, times(1)).notifyWarning(anyString(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue()).containsEntry("serverError", 20L);
        assertThat(ctxCaptor.getValue()).containsEntry("total", 70L);
    }

    @DisplayName("1차에 server_error 미등장, 2차에 server_error Counter 신규 등장 시 delta=current(server_error) 로 정상 계산.")
    @Test
    void lazyOutcome_serverErrorAppearsLater_deltaEqualsCurrent() {
        // arrange : 1차 — success 만 존재
        meterRegistry.counter("gemini.calls.total", "outcome", "success").increment(100);

        // act : 1차
        scheduler.runMonitoringTick();
        verify(slackNotifier, never()).notifyWarning(anyString(), any());

        // arrange : 2차 직전 server_error 가 신규 등장 + success 추가
        // success delta = 0(=100-100), server_error delta = 20(첫 등장이므로 prev=0)
        meterRegistry.counter("gemini.calls.total", "outcome", "server_error").increment(20);

        // act : 2차 — effectiveTotal=20, server_error/total = 20/20 = 100% > 10%
        scheduler.runMonitoringTick();

        // assert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(slackNotifier, times(1)).notifyWarning(anyString(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue()).containsEntry("serverError", 20L);
        assertThat(ctxCaptor.getValue()).containsEntry("total", 20L);
    }
}
