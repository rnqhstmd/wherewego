package com.wherewego.domain.place.parser;

import com.wherewego.domain.chatbot.ChatbotContext;

import java.util.Optional;

/**
 * URL → {@link ParsedContent} 변환 인터페이스.
 * <p>각 구현체는 자신이 지원하는 URL 패턴을 {@link #supports(String)} 로 선언한다.</p>
 */
public interface ContentParser {

    boolean supports(String url);

    Optional<ParsedContent> parse(String url, ChatbotContext ctx);
}
