package com.wherewego.spike.instagram;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * og:description에서 장소명을 추출한다.
 * 설계서 §3.4.3 regex 3개, 우선순위 EMOJI_PIN → KEYWORD → HASHTAG.
 */
public class PlaceNameExtractor {

    public static class ExtractionResult {
        public final String placeName;
        public final String matchedPattern;

        public ExtractionResult(String placeName, String matchedPattern) {
            this.placeName = placeName;
            this.matchedPattern = matchedPattern;
        }
    }

    private static final Pattern EMOJI_PIN = Pattern.compile("📍\\s*([^\\n#]+?)(?=\\s*[\\n#]|$)");
    private static final Pattern KEYWORD = Pattern.compile("(?:장소[:：]\\s*|at\\s+@?|in\\s+@?)([\\w가-힣 ]{2,30})");
    private static final Pattern HASHTAG = Pattern.compile("#([\\w가-힣]{2,30})");

    public Optional<ExtractionResult> extract(String ogDescription) {
        if (ogDescription == null || ogDescription.isBlank()) {
            return Optional.empty();
        }

        Matcher emojiMatcher = EMOJI_PIN.matcher(ogDescription);
        if (emojiMatcher.find()) {
            String place = emojiMatcher.group(1).trim();
            if (!place.isEmpty()) {
                return Optional.of(new ExtractionResult(place, "EMOJI_PIN"));
            }
        }

        Matcher keywordMatcher = KEYWORD.matcher(ogDescription);
        if (keywordMatcher.find()) {
            String place = keywordMatcher.group(1).trim();
            if (!place.isEmpty()) {
                return Optional.of(new ExtractionResult(place, "KEYWORD"));
            }
        }

        Matcher hashtagMatcher = HASHTAG.matcher(ogDescription);
        if (hashtagMatcher.find()) {
            String place = hashtagMatcher.group(1).trim();
            if (!place.isEmpty()) {
                return Optional.of(new ExtractionResult(place, "HASHTAG"));
            }
        }

        return Optional.empty();
    }
}
