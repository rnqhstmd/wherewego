package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.place.google.GooglePlacesClient;
import com.wherewego.support.error.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 키워드를 Google Places Text Search 에 위임하여 {@link PlaceSearchOutcome} 으로 분기 변환한다.
 *
 * <p>이전에는 카카오 Local API 를 1차로 사용하고 Google 을 폴백으로 두었으나, 본 단계에서는
 * 웹·챗봇 양쪽 모두 Google 만 사용하도록 단순화했다. KakaoLocalClient 의존성은 제거되었고
 * 추후 카카오 권한이 복구되면 폴백 체인을 다시 복원할 수 있다.</p>
 */
@Service
public class PlaceSearchService {

    private static final int MAX_MULTIPLE = 5;
    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);

    private final GooglePlacesClient googlePlacesClient;
    private final PlaceProperties placeProperties;

    public PlaceSearchService(GooglePlacesClient googlePlacesClient,
                              PlaceProperties placeProperties) {
        this.googlePlacesClient = googlePlacesClient;
        this.placeProperties = placeProperties;
    }

    /** 챗봇 흐름. 데드라인은 호출자(ChatbotContext)가 통제. */
    public PlaceSearchOutcome searchByKeyword(String keyword, ChatbotContext ctx) {
        if (ctx.expired()) {
            log.warn("Place search cutoff before request keyword={}", keyword);
            return new PlaceSearchOutcome.Empty();
        }
        int size = placeProperties.search().kakaoLocalSize();
        try {
            List<PlaceSearchHit> hits = googlePlacesClient.searchByKeyword(keyword, size, ctx);
            return toOutcome(hits);
        } catch (CoreException e) {
            log.warn("Place search failed keyword={} errorCode={}", keyword, e.getErrorType().getCode());
            return new PlaceSearchOutcome.Empty();
        }
    }

    /**
     * 웹/모바일 검색 (Phase 6 FR-API-2).
     * Google Places Text Search 한 번만 호출하고 결과를 그대로 반환.
     */
    public PlaceSearchOutcome searchByKeyword(String keyword) {
        ChatbotContext ctx = ChatbotContext.start(3_500L);
        try {
            List<PlaceSearchHit> hits = googlePlacesClient.searchByKeyword(keyword, MAX_MULTIPLE, ctx);
            return toOutcome(hits);
        } catch (CoreException e) {
            log.warn("Web place search failed keyword={} errorCode={}",
                    keyword, e.getErrorType().getCode());
            return new PlaceSearchOutcome.Empty();
        }
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
