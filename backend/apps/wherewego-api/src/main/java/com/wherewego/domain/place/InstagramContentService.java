package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.scraper.instagram.InstagramScraperClient;
import com.wherewego.infrastructure.scraper.instagram.MetaExtractor;
import com.wherewego.infrastructure.scraper.instagram.PlaceNameExtractor;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 인스타그램 URL 에서 장소 키워드를 추출한다.
 *
 * <ul>
 *     <li>feature flag {@code place.instagram.scraping-enabled=false} 시 즉시 {@link Optional#empty()}</li>
 *     <li>데드라인 초과 시 {@code PLC_INSTAGRAM_SCRAPE_FAILED} throw</li>
 *     <li>3-stage 스크래핑 모두 차단되면 {@link Optional#empty()} (호출자가 폴백 응답 결정)</li>
 * </ul>
 */
@Service
public class InstagramContentService {

    private static final Logger log = LoggerFactory.getLogger(InstagramContentService.class);
    private static final int CAPTION_SNIPPET_MAX = 120;

    private final PlaceProperties placeProperties;
    private final InstagramScraperClient scraperClient;
    private final MetaExtractor metaExtractor;
    private final PlaceNameExtractor placeNameExtractor;

    public InstagramContentService(PlaceProperties placeProperties,
                                   InstagramScraperClient scraperClient) {
        this.placeProperties = placeProperties;
        this.scraperClient = scraperClient;
        this.metaExtractor = new MetaExtractor();
        this.placeNameExtractor = new PlaceNameExtractor();
    }

    public Optional<InstagramExtraction> extract(String url, ChatbotContext ctx) {
        if (!placeProperties.instagram().scrapingEnabled()) {
            log.info("Instagram scraping disabled (feature flag) url={}", url);
            return Optional.empty();
        }

        if (ctx.expired()) {
            log.warn("Instagram extract cutoff before scrape url={}", url);
            throw new CoreException(ErrorType.PLC_INSTAGRAM_SCRAPE_FAILED, "처리가 지연되었어요. 다시 시도해 주세요.");
        }

        Optional<String> html = scraperClient.fetchHtml(url, ctx);
        if (html.isEmpty()) {
            log.warn("Instagram scrape returned empty url={}", url);
            return Optional.empty();
        }

        MetaExtractor.MetaResult meta = metaExtractor.extract(html.get());
        if (meta.isEmpty()) {
            log.warn("Instagram meta empty url={}", url);
            return Optional.empty();
        }

        Optional<PlaceNameExtractor.ExtractionResult> placeName = placeNameExtractor.extract(meta.ogDescription);
        if (placeName.isEmpty()) {
            log.info("Instagram place name not extracted url={}", url);
            return Optional.empty();
        }

        String snippet = truncate(meta.ogDescription, CAPTION_SNIPPET_MAX);
        return Optional.of(new InstagramExtraction(placeName.get().placeName, snippet));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
