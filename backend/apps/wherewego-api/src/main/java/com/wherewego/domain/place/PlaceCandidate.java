package com.wherewego.domain.place;

/**
 * Gemini가 캡션에서 추출한 장소명 + 신뢰도.
 *
 * <ul>
 *   <li>{@code confident=true}: 지역명+상호명 조합 등 동명 가능성 거의 없는 구체적 이름.
 *       Google 검색 결과의 첫 번째를 그대로 자동 등록한다.</li>
 *   <li>{@code confident=false}: 일반적이거나 동명 가능성 있는 모호한 이름.
 *       Google 검색 결과를 카드로 노출하여 사용자가 5개 중 선택한다.</li>
 * </ul>
 */
public record PlaceCandidate(String name, boolean confident) { }
