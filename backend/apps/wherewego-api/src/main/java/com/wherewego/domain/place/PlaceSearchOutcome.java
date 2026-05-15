package com.wherewego.domain.place;

import java.util.List;

/**
 * 카카오 Local 키워드 검색 결과 분기.
 * <ul>
 *     <li>{@link Single} : size = 1</li>
 *     <li>{@link Multiple} : 2 ~ 5</li>
 *     <li>{@link Empty} : 0 또는 데드라인/실패</li>
 * </ul>
 */
public sealed interface PlaceSearchOutcome
        permits PlaceSearchOutcome.Single, PlaceSearchOutcome.Multiple, PlaceSearchOutcome.Empty {

    record Single(PlaceSearchHit hit) implements PlaceSearchOutcome { }

    record Multiple(List<PlaceSearchHit> hits) implements PlaceSearchOutcome { }

    record Empty() implements PlaceSearchOutcome { }
}
