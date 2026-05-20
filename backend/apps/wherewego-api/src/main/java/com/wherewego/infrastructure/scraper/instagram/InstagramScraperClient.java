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

    private static final String API_NAME = "instagram";
    private static final String OP_FETCH_HTML = "fetchHtml";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_BLOCKED = "blocked";
    private static final String OUTCOME_TIMEOUT = "timeout";
    private static final String OUTCOME_ERROR = "error";
    private static final String CACHE_NA = "n/a";

    private final HtmlFetcher htmlFetcher;
    private final PlaceProperties placeProperties;
    private final InstagramBlockedRateTracker tracker;

    public InstagramScraperClient(PlaceProperties placeProperties,
                                  HtmlFetcher htmlFetcher,
                                  InstagramBlockedRateTracker tracker) {
        this.htmlFetcher = htmlFetcher;
        this.placeProperties = placeProperties;
        this.tracker = tracker;
    }

    /**
     * 3-stage 헤더 전략으로 HTML 본문을 시도. 모든 stage 차단되면 빈 Optional.
     * 데드라인 초과 시 즉시 빈 Optional 반환.
     */
    public Optional<String> fetchHtml(String url, ChatbotContext ctx) {
        int timeoutMs = placeProperties.scraper().instagram().timeoutMs();
        String safeUrl = safeForLog(url);

        long start = System.currentTimeMillis();
        String outcome = OUTCOME_ERROR;
        try {
            for (HtmlFetcher.Strategy strategy : HtmlFetcher.Strategy.values()) {
                long remaining = ctx.remaining();
                if (remaining <= 0) {
                    log.warn("Instagram scrape cutoff before strategy={} (deadline exceeded)", strategy);
                    outcome = OUTCOME_TIMEOUT;
                    return Optional.empty();
                }

                long effectiveMs = Math.min(remaining, timeoutMs);
                Duration timeout = Duration.ofMillis(effectiveMs);

                HtmlFetcher.FetchResult result = htmlFetcher.fetch(url, strategy, timeout);
                if (!result.blocked) {
                    log.info("Instagram scrape ok url={} strategy={} elapsed={}ms", safeUrl, strategy, result.elapsedMs);
                    outcome = OUTCOME_SUCCESS;
                    return Optional.of(result.body);
                }

                log.debug("Instagram scrape blocked url={} strategy={} status={} elapsed={}ms",
                        safeUrl, strategy, result.statusCode, result.elapsedMs);

                if (result.elapsedMs > effectiveMs) {
                    log.warn("Instagram scrape stage exceeded timeout url={} strategy={} elapsed={}ms timeoutMs={}",
                            safeUrl, strategy, result.elapsedMs, effectiveMs);
                }
            }

            log.warn("Instagram scrape all stages blocked url={}", safeUrl);
            outcome = OUTCOME_BLOCKED;
            return Optional.empty();
        } finally {
            // 순서: recordAttempt → recordBlocked. attempts 가 항상 blocked 이상이도록 보장.
            try {
                tracker.recordAttempt();
            } catch (RuntimeException ex) {
                log.warn("Tracker recordAttempt failed: {}", ex.getMessage());
            }
            if (OUTCOME_BLOCKED.equals(outcome)) {
                try {
                    // tracker 내부에서 safeForLog 적용 — 이중 sanitize 방지를 위해 원본 url 전달.
                    tracker.recordBlocked(url);
                } catch (RuntimeException ex) {
                    log.warn("Tracker recordBlocked failed: {}", ex.getMessage());
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("api={} op={} duration_ms={} outcome={} cache={}",
                    API_NAME, OP_FETCH_HTML, elapsed, outcome, CACHE_NA);
        }
    }

    /**
     * 로그 인젝션 방지: 외부 입력(URL 등) 내 CRLF를 무력화하여 로그 라인 위변조를 차단한다.
     */
    private static String safeForLog(String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }
}
