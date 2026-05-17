package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.place.google.GooglePlacesClient;
import com.wherewego.infrastructure.place.kakao.KakaoLocalClient;
import com.wherewego.support.error.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 키워드를 카카오 Local 검색에 위임하여 {@link PlaceSearchOutcome} 으로 분기 변환한다.
 *
 * <ul>
 *     <li>데드라인 초과 또는 카카오 API 실패 → {@link PlaceSearchOutcome.Empty}</li>
 *     <li>size=1 → {@link PlaceSearchOutcome.Single}, 2~5 → {@link PlaceSearchOutcome.Multiple}, 0 → Empty</li>
 *     <li>최대 5건으로 잘라낸다 ({@code place.search.kakao-local-size})</li>
 * </ul>
 *
 * <p>Phase 6 추가 (FR-API-2): 무인증 웹 검색 오버로드 {@link #searchByKeyword(String)} —
 * 카카오 0건/실패 시 Google Places 로 동기 폴백한다. 챗봇 비동기 흐름과 분리하여
 * 콜백/슬랙/큐 없이 단순 동기 호출만 수행한다.</p>
 */
@Service
public class PlaceSearchService {

    private static final int MAX_MULTIPLE = 5;
    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);

    // 웹 오버로드 전용: 카카오 + Google 동기 폴백 전체 데드라인 (설계서 §B2)
    private static final long WEB_TOTAL_DEADLINE_MS = 3_500L;
    // 카카오 단계에 할당하는 데드라인 (KakaoLocalClient 의 read timeout 기준)
    private static final long WEB_KAKAO_DEADLINE_MS = 3_000L;
    // 잔여 데드라인이 이 값보다 작으면 Google 폴백을 생략하고 Empty 반환
    private static final long WEB_FALLBACK_MIN_REMAINING_MS = 500L;

    private final KakaoLocalClient kakaoLocalClient;
    private final GooglePlacesClient googlePlacesClient;
    private final PlaceProperties placeProperties;

    public PlaceSearchService(KakaoLocalClient kakaoLocalClient,
                              GooglePlacesClient googlePlacesClient,
                              PlaceProperties placeProperties) {
        this.kakaoLocalClient = kakaoLocalClient;
        this.googlePlacesClient = googlePlacesClient;
        this.placeProperties = placeProperties;
    }

    public PlaceSearchOutcome searchByKeyword(String keyword, ChatbotContext ctx) {
        if (ctx.expired()) {
            log.warn("Place search cutoff before request keyword={}", keyword);
            return new PlaceSearchOutcome.Empty();
        }

        int size = placeProperties.search().kakaoLocalSize();

        List<PlaceSearchHit> hits;
        try {
            hits = kakaoLocalClient.searchByKeyword(keyword, size, ctx);
        } catch (CoreException e) {
            log.warn("Place search failed keyword={} errorCode={}", keyword, e.getErrorType().getCode());
            return new PlaceSearchOutcome.Empty();
        }

        return toOutcome(hits);
    }

    /**
     * 웹/모바일 검색 오버로드 (Phase 6 FR-API-2).
     * <p>인증된 사용자가 {@code GET /api/v1/places/search?q=} 로 호출하는 동기 검색.
     * 카카오 우선 → 0건/실패 시 Google Places 동기 폴백 → 둘 다 실패/0건이면 {@link PlaceSearchOutcome.Empty}.</p>
     * <p>챗봇 흐름의 {@code searchByKeyword(String, ChatbotContext)} 와 다르게 콜백/슬랙/큐 없이
     * 단순 동기 흐름만 수행하며, 전체 데드라인 {@value #WEB_TOTAL_DEADLINE_MS}ms 내에서 잔여 시간을 분배한다.</p>
     */
    public PlaceSearchOutcome searchByKeyword(String keyword) {
        Instant start = Instant.now();
        int size = placeProperties.search().kakaoLocalSize();

        ChatbotContext kakaoCtx = ChatbotContext.start(WEB_KAKAO_DEADLINE_MS);

        List<PlaceSearchHit> kakaoHits;
        boolean kakaoFailed = false;
        try {
            kakaoHits = kakaoLocalClient.searchByKeyword(keyword, size, kakaoCtx);
        } catch (CoreException e) {
            log.warn("Web place search kakao failed keyword={} errorCode={}",
                    keyword, e.getErrorType().getCode());
            kakaoHits = List.of();
            kakaoFailed = true;
        }

        if (!kakaoFailed && kakaoHits != null && !kakaoHits.isEmpty()) {
            return toOutcome(kakaoHits);
        }

        // Google 동기 폴백
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        long remaining = WEB_TOTAL_DEADLINE_MS - elapsed;
        if (remaining < WEB_FALLBACK_MIN_REMAINING_MS) {
            log.warn("Web place search skipping google fallback keyword={} remainingMs={}",
                    keyword, remaining);
            return new PlaceSearchOutcome.Empty();
        }

        ChatbotContext googleCtx = ChatbotContext.start(remaining);
        List<PlaceSearchHit> googleHits;
        try {
            googleHits = googlePlacesClient.searchByKeyword(keyword, MAX_MULTIPLE, googleCtx);
        } catch (CoreException e) {
            log.warn("Web place search google fallback failed keyword={} errorCode={}",
                    keyword, e.getErrorType().getCode());
            return new PlaceSearchOutcome.Empty();
        }

        return toOutcome(googleHits);
    }

    private PlaceSearchOutcome toOutcome(List<PlaceSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new PlaceSearchOutcome.Empty();
        }
        if (hits.size() == 1) {
            return new PlaceSearchOutcome.Single(hits.get(0));
        }
        List<PlaceSearchHit> trimmed = hits.size() > MAX_MULTIPLE
                ? hits.subList(0, MAX_MULTIPLE)
                : hits;
        return new PlaceSearchOutcome.Multiple(List.copyOf(trimmed));
    }
}
