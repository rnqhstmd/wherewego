package com.wherewego.infrastructure.scraper.instagram;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * og:description에서 likes/comments 카운트 앞부분 접미사를 정제하여
 * 순수 캡션 텍스트를 반환한다.
 *
 * <p>예) {@code 1,234 likes, 56 comments - user on October 1, 2024: "오늘 다녀온 카페".}
 * → {@code 오늘 다녀온 카페}</p>
 *
 * <p>regex {@code /:\s*"(.+)"\.?\s*$/} 매칭 시 캡처 그룹 1만 반환,
 * 매칭 실패 시 입력 원문 trim 반환. null/blank 입력은 빈 문자열 반환.</p>
 */
@Component
public class CaptionCleaner {

    private static final Pattern CAPTION_PATTERN =
            Pattern.compile(":\\s*\"(.+)\"\\.?\\s*$", Pattern.DOTALL);

    public String clean(String ogDescription) {
        if (ogDescription == null || ogDescription.isBlank()) {
            return "";
        }
        Matcher matcher = CAPTION_PATTERN.matcher(ogDescription);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return ogDescription.trim();
    }
}
