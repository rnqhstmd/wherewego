package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.place.kakao.KakaoLocalClient;
import com.wherewego.support.error.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 키워드를 카카오 Local 검색에 위임하여 {@link PlaceSearchOutcome} 으로 분기 변환한다.
 *
 * <ul>
 *     <li>데드라인 초과 또는 카카오 API 실패 → {@link PlaceSearchOutcome.Empty}</li>
 *     <li>size=1 → {@link PlaceSearchOutcome.Single}, 2~5 → {@link PlaceSearchOutcome.Multiple}, 0 → Empty</li>
 *     <li>최대 5건으로 잘라낸다 ({@code place.search.kakao-local-size})</li>
 * </ul>
 */
@Service
public class PlaceSearchService {

    private static final int MAX_MULTIPLE = 5;
    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);

    private final KakaoLocalClient kakaoLocalClient;
    private final PlaceProperties placeProperties;

    public PlaceSearchService(KakaoLocalClient kakaoLocalClient,
                              PlaceProperties placeProperties) {
        this.kakaoLocalClient = kakaoLocalClient;
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
