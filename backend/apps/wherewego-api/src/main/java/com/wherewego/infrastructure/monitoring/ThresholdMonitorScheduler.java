package com.wherewego.infrastructure.monitoring;

import com.wherewego.config.env.MonitoringThresholdProperties;
import com.wherewego.config.security.RequestIdFilter;
import com.wherewego.infrastructure.notify.slack.SlackNotifier;
import com.wherewego.infrastructure.scraper.instagram.InstagramBlockedRateTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 외부 API 임계값 모니터링 스케줄러.
 *
 * <p>1시간 단위 실행하며 (1) Gemini 5xx(server_error) 비율 10% 초과 시 SlackNotifier.notifyWarning,
 * (2) Instagram 차단율 50% 초과 시 SlackNotifier.notifyFailure 발송한다.</p>
 *
 * <p>각 check는 개별 try-catch로 격리되어 한쪽 실패가 다른쪽 알림 누락을 유발하지 않는다 (NFR-4).</p>
 *
 * <p>초기 1h 지연 후 매 1h마다 실행 — 배포 직후 누적치(서버 시작 전 카운터)가 첫 윈도우 델타로 잡혀 오탐되는 것을 방지한다.</p>
 */
@Component
public final class ThresholdMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(ThresholdMonitorScheduler.class);

    /** 윈도우 / 스케줄러 발사 주기 (1h). 외부화하지 않음 — yml 키와 동기화 위험 회피. */
    static final long WINDOW_MS = 3_600_000L;
    private static final String COOLDOWN_KEY_GEMINI_5XX = "gemini.5xx";
    private static final String COOLDOWN_KEY_INSTAGRAM_BLOCKED = "instagram.blocked";
    private static final String GEMINI_METRIC_NAME = "gemini.calls.total";
    private static final String GEMINI_OUTCOME_DISABLED = "disabled";
    private static final String GEMINI_OUTCOME_SERVER_ERROR = "server_error";

    private final MeterRegistry meterRegistry;
    private final InstagramBlockedRateTracker tracker;
    private final SlackNotifier slackNotifier;
    private final MonitoringThresholdProperties props;

    /** outcome → 직전 스냅샷 누적값. @Scheduled 단일 스레드에서만 사용하나 보수적으로 ConcurrentHashMap. */
    private final Map<String, Double> previousGeminiSnapshot = new ConcurrentHashMap<>();
    /** cooldown 키 → 마지막 발송 Epoch ms. */
    private final Map<String, Long> cooldownEpochMs = new ConcurrentHashMap<>();

    public ThresholdMonitorScheduler(MeterRegistry meterRegistry,
                                     InstagramBlockedRateTracker tracker,
                                     SlackNotifier slackNotifier,
                                     MonitoringThresholdProperties props) {
        this.meterRegistry = meterRegistry;
        this.tracker = tracker;
        this.slackNotifier = slackNotifier;
        this.props = props;
    }

    @Scheduled(fixedRate = WINDOW_MS, initialDelay = WINDOW_MS)
    public void runMonitoringTick() {
        // NFR-4: 본문 최상위 try-catch(Exception) — MDC 조작이나 check 사이 외부 예외도 swallow.
        // 다음 1h tick의 정상 재실행을 보장한다 (ReelSelectionAutoSaveScheduler 패턴).
        try {
            // MUST-ADDRESS 4: MDC SCHEDULER 마커는 진입부 1회. 두 check가 같은 MDC 컨텍스트 공유.
            MDC.put(RequestIdFilter.MDC_KEY, "SCHEDULER");
            try {
                // 각 check를 개별 try-catch로 분리 — 한쪽 실패가 다른쪽 알림 누락시키지 않음.
                try {
                    checkGeminiServerErrorRate();
                } catch (Exception e) {
                    log.error("Gemini 5xx threshold check failed", e);
                }
                try {
                    checkInstagramBlockedRate();
                } catch (Exception e) {
                    log.error("Instagram blocked threshold check failed", e);
                }
            } finally {
                MDC.clear();
            }
        } catch (Exception e) {
            // MDC put 자체나 finally 외부에서 발생할 수 있는 예외까지 최종 swallow.
            log.error("Threshold monitoring tick failed unexpectedly", e);
        }
    }

    /** Gemini 5xx 비율 = server_error 델타 / (전체 델타 - disabled 델타). 10% 초과 + 5분 쿨다운. */
    void checkGeminiServerErrorRate() {
        Map<String, Double> current = snapshotGeminiCounters();
        Map<String, Double> delta = new HashMap<>();
        for (Map.Entry<String, Double> e : current.entrySet()) {
            // MUST-ADDRESS 2: outcome lazy 등장 대응. previousSnapshot에 키가 없으면 0으로 간주.
            double prev = previousGeminiSnapshot.getOrDefault(e.getKey(), 0.0);
            delta.put(e.getKey(), Math.max(0.0, e.getValue() - prev));
        }
        previousGeminiSnapshot.clear();
        previousGeminiSnapshot.putAll(current);

        double totalDelta = delta.values().stream().mapToDouble(Double::doubleValue).sum();
        double disabledDelta = delta.getOrDefault(GEMINI_OUTCOME_DISABLED, 0.0);
        double effectiveTotal = totalDelta - disabledDelta;
        if (effectiveTotal < 1.0) {
            return; // FR-10-2 분모 1건 미만 스킵
        }

        double serverErrorDelta = delta.getOrDefault(GEMINI_OUTCOME_SERVER_ERROR, 0.0);
        double ratio = serverErrorDelta / effectiveTotal;
        double threshold = props.gemini().serverErrorRate();

        long now = System.currentTimeMillis();
        long geminiCooldownMs = props.gemini().cooldownMinutes() * 60_000L;
        if (ratio > threshold && cooldownPassed(COOLDOWN_KEY_GEMINI_5XX, now, geminiCooldownMs)) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("windowHours", 1);
            ctx.put("serverError", (long) serverErrorDelta);
            ctx.put("total", (long) effectiveTotal);
            ctx.put("ratioPct", String.format("%.1f%%", ratio * 100));
            ctx.put("thresholdPct", String.format("%.1f%%", threshold * 100));
            slackNotifier.notifyWarning("Gemini 5xx 비율 임계 초과", ctx);
            // 발송 시도 결과 무관하게 cooldown 업데이트 (위험/미해결 합의).
            cooldownEpochMs.put(COOLDOWN_KEY_GEMINI_5XX, now);
        }
    }

    /** Instagram 차단율 = blocked / attempts. attempts=0 시 스킵 (FR-11-3). 50% 초과 + 쿨다운 통과 시 notifyFailure. */
    void checkInstagramBlockedRate() {
        InstagramBlockedRateTracker.Snapshot snap = tracker.flushWindow();
        if (snap.attempts() < 1) {
            return; // FR-11-3 + divide-by-zero 가드
        }
        double rate = snap.blocked() / (double) snap.attempts();
        double threshold = props.instagram().blockedRate();
        long now = System.currentTimeMillis();
        long instagramCooldownMs = props.instagram().cooldownMinutes() * 60_000L;
        if (rate > threshold && cooldownPassed(COOLDOWN_KEY_INSTAGRAM_BLOCKED, now, instagramCooldownMs)) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("windowHours", 1);
            ctx.put("attempts", snap.attempts());
            ctx.put("blocked", snap.blocked());
            ctx.put("ratePct", String.format("%.1f%%", rate * 100));
            ctx.put("thresholdPct", String.format("%.1f%%", threshold * 100));
            ctx.put("lastBlockedUrl", snap.lastBlockedUrl() == null ? "n/a" : snap.lastBlockedUrl());
            slackNotifier.notifyFailure("Instagram 차단율 임계 초과", ctx);
            // 발송 시도 결과 무관하게 cooldown 업데이트 (Gemini와 동일 정책).
            cooldownEpochMs.put(COOLDOWN_KEY_INSTAGRAM_BLOCKED, now);
        }
    }

    /** MeterRegistry에서 gemini.calls.total Counter들을 outcome별로 누적값 수집. */
    private Map<String, Double> snapshotGeminiCounters() {
        Map<String, Double> snapshot = new HashMap<>();
        for (Counter c : meterRegistry.find(GEMINI_METRIC_NAME).counters()) {
            String outcome = c.getId().getTag("outcome");
            if (outcome == null) continue;
            snapshot.put(outcome, c.count());
        }
        return snapshot;
    }

    /** cooldown 통과 여부. 마지막 발송 후 {@code cooldownMs} ms 경과 시 true. 키별 cooldown 분리. */
    private boolean cooldownPassed(String key, long nowMs, long cooldownMs) {
        Long last = cooldownEpochMs.get(key);
        if (last == null) return true;
        return nowMs - last >= cooldownMs;
    }
}
