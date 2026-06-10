package com.wherewego.domain.chat;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.scraper.instagram.InstagramScraperClient;
import com.wherewego.infrastructure.scraper.instagram.MetaExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * GC-3(FR-GC3-2): REEL_LINK 썸네일 비동기 스크래핑 오케스트레이션.
 *
 * <p>{@link GroupChatService#postMessage} 가 전송 트랜잭션 커밋 후(afterCommit) {@link #captureAsync}를 호출한다.
 * flag 게이트(scrapingEnabled AND reelThumbnailEnabled) → 3-stage fetch → og:image 추출 → {@link ReelThumbnailWriter}
 * 반영 순으로 진행하며, 전송 트랜잭션/응답은 어떤 경우에도 막지 않는다(별도 스레드 + best-effort).</p>
 *
 * <p>의도적으로 클래스 트랜잭션이 없다 — 외부 호출(스크래핑)이 DB 커넥션을 점유하지 않게 한다. payload 갱신은
 * {@link ReelThumbnailWriter}(다른 빈)에 위임하여 @Async self-invocation 으로 @Transactional 이 우회되는 문제를
 * 피한다(BotChatProcessor 선례). 미처리 예외는 {@code log.warn} 후 삼킨다(@Async 핸들러 부담 최소화).</p>
 */
@Slf4j
@Service
public class ReelThumbnailService {

    private final PlaceProperties placeProperties;
    private final InstagramScraperClient scraperClient;
    private final ReelThumbnailWriter writer;
    private final MetaExtractor metaExtractor = new MetaExtractor();

    public ReelThumbnailService(PlaceProperties placeProperties,
                                InstagramScraperClient scraperClient,
                                ReelThumbnailWriter writer) {
        this.placeProperties = placeProperties;
        this.scraperClient = scraperClient;
        this.writer = writer;
    }

    /**
     * REEL_LINK 메시지의 og:image 를 스크래핑하여 썸네일 payload 에 반영한다(GC-3). 봇 채팅 풀을 재사용한다
     * ({@code botChatExecutor} — MVP 신규 surface 최소화, 설계 §7).
     *
     * @param messageId 대상 REEL_LINK 메시지 ID
     * @param url       검증된 인스타 릴스 URL(전송 시 payload 에 저장된 값)
     */
    @Async("botChatExecutor")
    public void captureAsync(Long messageId, String url) {
        try {
            // flag 게이트: 마스터(scrapingEnabled) AND 썸네일 전용(reelThumbnailEnabled) — off면 즉시 종료(thumbnailUrl null).
            if (!placeProperties.instagram().scrapingEnabled()
                    || !placeProperties.instagram().reelThumbnailEnabled()) {
                return;
            }

            ChatbotContext ctx = ChatbotContext.start(placeProperties.instagram().reelThumbnailDeadlineMs());
            Optional<String> html = scraperClient.fetchHtml(url, ctx);
            if (html.isEmpty()) {
                return;
            }

            String ogImage = metaExtractor.extract(html.get()).ogImage;
            if (ogImage == null || ogImage.isBlank()) {
                return;
            }

            writer.attach(messageId, ogImage);
        } catch (Exception e) {
            log.warn("릴스 썸네일 스크래핑 실패 (messageId={}): {}", messageId, e.getMessage());
        }
    }
}
