package com.wherewego.domain.place.parser;

import com.wherewego.domain.place.PlaceCandidate;

import java.util.List;

/**
 * 컨텐츠 파서의 공통 산출물.
 *  - {@code placeKeyword}: 1순위 키워드 (= candidates.get(0).name())
 *  - {@code captionSnippet}: 원본 캡션/설명 일부
 *  - {@code extraPlaceKeywords}: 구버전 호환 — 2순위 이후 키워드 string
 *  - {@code candidates}: 신버전 — PlaceCandidate (name + confident) 리스트
 */
public record ParsedContent(
        String placeKeyword,
        String captionSnippet,
        List<String> extraPlaceKeywords,
        List<PlaceCandidate> candidates
) {
    public ParsedContent {
        extraPlaceKeywords = extraPlaceKeywords == null
                ? List.of()
                : List.copyOf(extraPlaceKeywords);
        candidates = candidates == null
                ? List.of()
                : List.copyOf(candidates);
    }

    public ParsedContent(String placeKeyword, String captionSnippet) {
        this(placeKeyword, captionSnippet, List.of(), List.of());
    }

    public ParsedContent(String placeKeyword, String captionSnippet, List<String> extraPlaceKeywords) {
        this(placeKeyword, captionSnippet, extraPlaceKeywords, List.of());
    }
}
