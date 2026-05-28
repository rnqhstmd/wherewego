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
     * <p>Phase 13: 전체 추출 핀을 저장하되 {@code wishIndices} 에 든 것만 WISH, 나머지는 REEL 로 저장한다 (§2.1).
     * 따라서 더 이상 "어떤 핀을 저장할지"가 아니라 "어떤 핀을 위시로 저장할지"를 담는다.</p>
     *
     * @param state         현재 단계
     * @param instagramUrl  원본 인스타 URL
     * @param places        Gemini/카카오 추출 결과(1-based index 기준 정렬)
     * @param wishIndices   위시(WISH)로 저장할 1-based 인덱스 집합. 나머지 추출 핀은 REEL 로 저장.
     * @param expiresAt     세션 만료 시각 (참고용 — 실제 TTL은 Caffeine 이 관리)
     * @param pendingMemo   MEMO_WAITING 직전에 미리 받아둔 메모(없으면 null)
     */
    public record Snapshot(
            State state,
            String instagramUrl,
            List<PlaceSearchHit> places,
            Set<Integer> wishIndices,
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
     * 세션 갱신. TTL 은 최초 create 시점 기준으로 고정되며, 상태 전이 시 put 이 호출되어도
     * TTL 이 갱신되지 않는다 ({@link com.wherewego.config.cache.CacheConfig} 의 REEL_SELECTION
     * 커스텀 Expiry — expireAfterUpdate/expireAfterRead 모두 잔여 currentDuration 을 그대로 반환).
     * NFR-12-5 "최초 URL 전송 후 3분" 만료 기준을 보존하기 위한 의도된 동작이다.
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
