package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.gemini.GeminiPlaceClient;
import com.wherewego.infrastructure.scraper.instagram.CaptionCleaner;
import com.wherewego.infrastructure.scraper.instagram.InstagramScraperClient;
import com.wherewego.infrastructure.scraper.instagram.MetaExtractor;
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
 *     <li>Phase 2.5: regex 기반 {@code PlaceNameExtractor} → Gemini 2.0 Flash 호출로 전환.
 *     흐름은 {@code MetaExtractor → CaptionCleaner → GeminiPlaceClient}.</li>
 * </ul>
 */
@Service
public class InstagramContentService {

    private static final Logger log = LoggerFactory.getLogger(InstagramContentService.class);
    private static final int CAPTION_SNIPPET_MAX = 120;

    private final PlaceProperties placeProperties;
    private final InstagramScraperClient scraperClient;
    private final CaptionCleaner captionCleaner;
    private final GeminiPlaceClient geminiPlaceClient;
    private final MetaExtractor metaExtractor;

    public InstagramContentService(PlaceProperties placeProperties,
                                   InstagramScraperClient scraperClient,
                                   CaptionCleaner captionCleaner,
                                   GeminiPlaceClient geminiPlaceClient) {
        this.placeProperties = placeProperties;
        this.scraperClient = scraperClient;
        this.captionCleaner = captionCleaner;
        this.geminiPlaceClient = geminiPlaceClient;
        this.metaExtractor = new MetaExtractor();
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

        String cleanedCaption = captionCleaner.clean(meta.ogDescription);
        if (cleanedCaption.isBlank()) {
            log.info("Instagram cleaned caption blank url={}", url);
            return Optional.empty();
        }

        if (ctx.expired()) {
            log.warn("Instagram extract cutoff before Gemini call url={}", url);
            throw new CoreException(ErrorType.PLC_INSTAGRAM_SCRAPE_FAILED, "처리가 지연되었어요. 다시 시도해 주세요.");
        }

        Optional<String> placeKeyword = geminiPlaceClient.extractPlaceName(cleanedCaption, ctx.userId());
        if (placeKeyword.isEmpty()) {
            log.info("Instagram place name not extracted via Gemini url={}", url);
            return Optional.empty();
        }

        String snippet = truncate(cleanedCaption, CAPTION_SNIPPET_MAX);
        return Optional.of(new InstagramExtraction(placeKeyword.get(), snippet));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
