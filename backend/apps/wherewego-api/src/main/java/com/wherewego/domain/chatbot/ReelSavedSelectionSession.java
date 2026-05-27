package com.wherewego.domain.chatbot;

import com.wherewego.config.cache.CacheConfig;
import com.wherewego.domain.place.PlaceSearchHit;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Phase 12: 릴스 저장 선택 상태머신 세션.
 *
 * <p>key = botUserKey, value = {@link Snapshot}, TTL = {@code chatbot.reel.selection-ttl-seconds}
 * (기본 180초). PROCESSING/SINGLE_WANT/MULTI_SELECTING/BULK_SAVE/MEMO_WAITING 모든 단계의 상태를
 * 동일 TTL 윈도우로 통일한다 (D-3, D-4).</p>
 *
 * <p>상태 전이:
 * <ol>
 *   <li>인스타 URL 도착 → {@code PROCESSING} 진입(Gemini 호출 useCallback=true)</li>
 *   <li>Gemini 결과 도착 → 장소 수에 따라 {@code SINGLE_WANT}(1) / {@code MULTI_SELECTING}(2~30) /
 *       {@code BULK_SAVE}(31+) 진입</li>
 *   <li>사용자 응답 → {@code MEMO_WAITING}</li>
 *   <li>메모 입력 → 저장 + {@code COMPLETE}로 invalidate</li>
 * </ol></p>
 */
@Component
public class ReelSavedSelectionSession {

    private final CacheManager cacheManager;

    public ReelSavedSelectionSession(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public enum State {
        IDLE,
        PROCESSING,
        SINGLE_WANT,
        MULTI_SELECTING,
        BULK_SAVE,
        MEMO_WAITING,
        COMPLETE
    }

    /**
     * 불변 스냅샷. 상태 전이는 새 인스턴스를 만들어 {@link #put} 으로 덮어쓴다.
     *
     * @param state            현재 단계
     * @param instagramUrl     원본 인스타 URL
     * @param places           Gemini/카카오 추출 결과(1-based index 기준 정렬)
     * @param selectedIndices  사용자가 선택한 1-based 인덱스 집합 (MULTI_SELECTING/BULK_SAVE)
     * @param wantOnSelected   SINGLE_WANT 단계에서 "가고 싶어요" 선택 여부
     * @param expiresAt        세션 만료 시각 (참고용 — 실제 TTL은 Caffeine 이 관리)
     * @param pendingMemo      MEMO_WAITING 직전에 미리 받아둔 메모(없으면 null)
     */
    public record Snapshot(
            State state,
            String instagramUrl,
            List<PlaceSearchHit> places,
            Set<Integer> selectedIndices,
            boolean wantOnSelected,
            ZonedDateTime expiresAt,
            String pendingMemo
    ) {
    }

    /** 현재 세션 스냅샷 조회. 없으면 empty. */
    public Optional<Snapshot> peek(String botUserKey) {
        Cache.ValueWrapper wrapper = cache().get(botUserKey);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object value = wrapper.get();
        if (value instanceof Snapshot snapshot) {
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    /**
     * 세션 갱신. Caffeine 의 {@code expireAfterWrite} 특성상 매 put 마다 TTL 이 갱신된다.
     * 단, 사용자 인-액션 중 TTL 을 리셋하지 않기로 한 단계(MULTI_SELECTING 재시도 등)에서는
     * 호출자가 {@link Snapshot#expiresAt}을 직접 유지하더라도 캐시 TTL 은 새로 잡힘에 주의.
     * (3분 TTL 자체가 충분히 길어 실용상 영향은 미미함 — D-4)
     */
    public void put(String botUserKey, Snapshot snapshot) {
        cache().put(botUserKey, snapshot);
    }

    /** 세션 종료. COMPLETE 단계 또는 명시적 취소 시 호출. */
    public void invalidate(String botUserKey) {
        cache().evict(botUserKey);
    }

    /**
     * 상태 전이 편의 메서드. 현재 스냅샷이 없으면 no-op 후 empty 반환.
     * 호출자가 atomic 한 RMW 가 필요한 경우에는 {@link #peek} → {@link #put} 직접 호출 권장
     * (Caffeine 은 본 메서드에서 별도 잠금을 제공하지 않으므로 race 가능).
     */
    public Optional<Snapshot> updateState(String botUserKey, UnaryOperator<Snapshot> updater) {
        Optional<Snapshot> current = peek(botUserKey);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Snapshot next = updater.apply(current.get());
        put(botUserKey, next);
        return Optional.of(next);
    }

    private Cache cache() {
        Cache cache = cacheManager.getCache(CacheConfig.REEL_SELECTION);
        if (cache == null) {
            throw new IllegalStateException("Cache not configured: " + CacheConfig.REEL_SELECTION);
        }
        return cache;
    }
}
