package com.wherewego.infrastructure.scraper.instagram;

import org.springframework.stereotype.Component;

/**
 * Instagram 스크래핑 차단율 추적기.
 *
 * <p>1시간 윈도우 내 attempts/blocked 카운터와 마지막 차단 URL을 기록한다.
 * 모든 상태 변경/스냅샷은 단일 락({@code lock})으로 직렬화하여 두 카운터의 race 가능성을 구조적으로 제거한다.</p>
 *
 * <p>{@link #flushWindow()}는 캡처와 동시에 카운터를 0으로 초기화하므로
 * "판단→발송→리셋"의 의미상 분리는 호출자(스케줄러)가 반환된 {@link Snapshot}으로 처리한다.</p>
 *
 * <p>{@code @Scheduled} 없음. 단순 상태 저장소.</p>
 */
@Component
public final class InstagramBlockedRateTracker {

    private final Object lock = new Object();
    private long attempts = 0L;
    private long blocked = 0L;
    private String lastBlockedUrl = null;

    public void recordAttempt() {
        synchronized (lock) {
            attempts++;
        }
    }

    public void recordBlocked(String url) {
        synchronized (lock) {
            blocked++;
            lastBlockedUrl = safeForLog(url);
        }
    }

    public Snapshot flushWindow() {
        synchronized (lock) {
            Snapshot s = new Snapshot(attempts, blocked, lastBlockedUrl);
            attempts = 0L;
            blocked = 0L;
            lastBlockedUrl = null;
            return s;
        }
    }

    public record Snapshot(long attempts, long blocked, String lastBlockedUrl) { }

    /**
     * 로그 인젝션 방지: 외부 입력 내 CRLF를 무력화하여 로그 라인 위변조를 차단한다.
     */
    private static String safeForLog(String v) {
        return v == null ? null : v.replace('\r', '_').replace('\n', '_');
    }
}
