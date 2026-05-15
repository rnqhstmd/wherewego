package com.wherewego.domain.place.parser;

/**
 * 컨텐츠 파서의 공통 산출물. {@code placeKeyword} 는 카카오 Local 검색용,
 * {@code captionSnippet} 은 원본 캡션/설명 일부 (디버그/로그용).
 */
public record ParsedContent(
        String placeKeyword,
        String captionSnippet
) { }
