package com.wherewego.domain.place;

import java.util.List;

/**
 * 인스타그램 스크래핑 결과.
 *  - {@code placeKeyword}: 기존 호환용 1순위 키워드 (= candidates.get(0).name())
 *  - {@code captionSnippet}: 원본 og:description 일부 (디버그/로그용)
 *  - {@code extraPlaceKeywords}: 구버전 호환용 — 2순위 이후 키워드 string 리스트
 *  - {@code candidates}: 신버전 — PlaceCandidate (name + confident) 리스트
 */
public record InstagramExtraction(
        String placeKeyword,
        String captionSnippet,
        List<String> extraPlaceKeywords,
        List<PlaceCandidate> candidates
) {
    public InstagramExtraction {
        extraPlaceKeywords = extraPlaceKeywords == null
                ? List.of()
                : List.copyOf(extraPlaceKeywords);
        candidates = candidates == null
                ? List.of()
                : List.copyOf(candidates);
    }

    /** 기존 호출처 호환: 단일 키워드 생성자. */
    public InstagramExtraction(String placeKeyword, String captionSnippet) {
        this(placeKeyword, captionSnippet, List.of(), List.of());
    }

    /** 구버전 호출처 호환: extras까지. */
    public InstagramExtraction(String placeKeyword, String captionSnippet, List<String> extraPlaceKeywords) {
        this(placeKeyword, captionSnippet, extraPlaceKeywords, List.of());
    }

    /** 신버전 진입점: candidates만 받아 자동으로 placeKeyword/extras 도출. */
    public static InstagramExtraction fromCandidates(List<PlaceCandidate> candidates, String snippet) {
        if (candidates == null || candidates.isEmpty()) {
            return new InstagramExtraction("", snippet, List.of(), List.of());
        }
        String primary = candidates.get(0).name();
        List<String> extras = candidates.size() > 1
                ? candidates.subList(1, candidates.size()).stream().map(PlaceCandidate::name).toList()
                : List.of();
        return new InstagramExtraction(primary, snippet, extras, candidates);
    }
}
