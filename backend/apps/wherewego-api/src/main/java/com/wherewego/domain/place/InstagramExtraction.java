package com.wherewego.domain.place;

/**
 * 인스타그램 스크래핑 결과. {@code placeKeyword} 는 카카오 Local 검색 키워드,
 * {@code captionSnippet} 은 원본 og:description 일부 (디버그/로그용).
 */
public record InstagramExtraction(
        String placeKeyword,
        String captionSnippet
) { }
