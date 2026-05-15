package com.wherewego.domain.place.parser;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 등록된 모든 {@link ContentParser} 중에서 URL 을 지원하는 첫 파서를 해결한다.
 * <p>미지원 URL → {@link Optional#empty()} (호출자가 폴백 응답 결정).</p>
 */
@Component
public class ContentParserRegistry {

    private final List<ContentParser> parsers;

    public ContentParserRegistry(List<ContentParser> parsers) {
        this.parsers = parsers;
    }

    public Optional<ContentParser> resolve(String url) {
        if (url == null) {
            return Optional.empty();
        }
        return parsers.stream()
                .filter(p -> p.supports(url))
                .findFirst();
    }
}
