package com.wherewego.infrastructure.scraper.instagram;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 인스타그램 페이지 HTML을 3-stage 우회 흐름 (NO_UA → CHROME_UA → FULL_HEADERS)으로 가져온다.
 * 각 stage 시작 전 {@link ChatbotContext#remaining()} 검사 → 즉시 {@link Optional#empty()}.
 *
 * <p>각 stage 타임아웃은 {@code min(ctx.remaining(), place.scraper.instagram.timeout-ms)}로 결정한다.</p>
 */
@Component
public class InstagramScraperClient {

    private static final Logger log = LoggerFactory.getLogger(InstagramScraperClient.class);

    private final HtmlFetcher htmlFetcher;
    private final PlaceProperties placeProperties;

    public InstagramScraperClient(PlaceProperties placeProperties, HtmlFetcher htmlFetcher) {
        this.htmlFetcher = htmlFetcher;
        this.placeProperties = placeProperties;
    }

    /**
     * 3-stage 헤더 전략으로 HTML 본문을 시도. 모든 stage 차단되면 빈 Optional.
     * 데드라인 초과 시 즉시 빈 Optional 반환.
     */
    public Optional<String> fetchHtml(String url, ChatbotContext ctx) {
        int timeoutMs = placeProperties.scraper().instagram().timeoutMs();

        for (HtmlFetcher.Strategy strategy : HtmlFetcher.Strategy.values()) {
            long remaining = ctx.remaining();
            if (remaining <= 0) {
                log.warn("Instagram scrape cutoff before strategy={} (deadline exceeded)", strategy);
                return Optional.empty();
            }

            long effectiveMs = Math.min(remaining, timeoutMs);
            Duration timeout = Duration.ofMillis(effectiveMs);

            HtmlFetcher.FetchResult result = htmlFetcher.fetch(url, strategy, timeout);
            if (!result.blocked) {
                log.info("Instagram scrape ok url={} strategy={} elapsed={}ms", url, strategy, result.elapsedMs);
                return Optional.of(result.body);
            }

            log.debug("Instagram scrape blocked url={} strategy={} status={} elapsed={}ms",
                    url, strategy, result.statusCode, result.elapsedMs);

            if (result.elapsedMs > effectiveMs) {
                log.warn("Instagram scrape stage exceeded timeout url={} strategy={} elapsed={}ms timeoutMs={}",
                        url, strategy, result.elapsedMs, effectiveMs);
            }
        }

        log.warn("Instagram scrape all stages blocked url={}", url);
        return Optional.empty();
    }
}
