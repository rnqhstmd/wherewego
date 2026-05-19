package com.wherewego.domain.place.parser;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.InstagramContentService;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 인스타그램 게시물/릴스 URL 을 {@link InstagramContentService} 로 위임 파싱한다.
 */
@Component
public class InstagramParser implements ContentParser {

    private static final Pattern URL = Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*$");

    private final InstagramContentService instagramContentService;

    public InstagramParser(InstagramContentService instagramContentService) {
        this.instagramContentService = instagramContentService;
    }

    @Override
    public boolean supports(String url) {
        return url != null && URL.matcher(url).matches();
    }

    @Override
    public Optional<ParsedContent> parse(String url, ChatbotContext ctx) {
        return instagramContentService.extract(url, ctx)
                .map(e -> new ParsedContent(
                        e.placeKeyword(),
                        e.captionSnippet(),
                        e.extraPlaceKeywords(),
                        e.candidates()));
    }
}
