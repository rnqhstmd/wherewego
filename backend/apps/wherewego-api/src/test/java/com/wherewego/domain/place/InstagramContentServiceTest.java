package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.gemini.GeminiPlaceClient;
import com.wherewego.infrastructure.scraper.instagram.CaptionCleaner;
import com.wherewego.infrastructure.scraper.instagram.InstagramScraperClient;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstagramContentServiceTest {

    @Mock
    private PlaceProperties placeProperties;

    @Mock
    private InstagramScraperClient scraperClient;

    @Mock
    private CaptionCleaner captionCleaner;

    @Mock
    private GeminiPlaceClient geminiPlaceClient;

    private InstagramContentService instagramContentService;

    private static PlaceProperties.Instagram instagram(boolean scrapingEnabled) {
        return new PlaceProperties.Instagram(scrapingEnabled, true, 5_000L);
    }

    @DisplayName("인스타그램 URL 에서 장소를 추출할 때,")
    @Nested
    class Extract {

        @DisplayName("scraping-enabled=false 면, 즉시 Empty 를 반환하고 scraperClient/Gemini 를 호출하지 않는다 (AC-18).")
        @Test
        void extract_scrapingDisabled_returnsEmpty() {
            // arrange
            when(placeProperties.instagram()).thenReturn(instagram(false));
            instagramContentService = new InstagramContentService(
                    placeProperties, scraperClient, captionCleaner, geminiPlaceClient);
            ChatbotContext ctx = ChatbotContext.start(5_000L);

            // act
            Optional<InstagramExtraction> result = instagramContentService.extract(
                    "https://www.instagram.com/p/ABC/", ctx
            );

            // assert
            assertThat(result).isEmpty();
            verify(scraperClient, never()).fetchHtml(anyString(), any(ChatbotContext.class));
            verify(geminiPlaceClient, never()).extractPlaceName(anyString(), nullable(Long.class));
        }

        @DisplayName("scraping-enabled=true 이고 정상 HTML + Gemini 응답이면, InstagramExtraction 을 반환한다.")
        @Test
        void extract_scrapingEnabled_returnsExtraction() {
            // arrange
            when(placeProperties.instagram()).thenReturn(instagram(true));
            instagramContentService = new InstagramContentService(
                    placeProperties, scraperClient, captionCleaner, geminiPlaceClient);
            ChatbotContext ctx = ChatbotContext.start(5_000L);
            String html = """
                    <html><head>
                    <meta property="og:title" content="Sample post">
                    <meta property="og:description" content="1,234 likes, 56 comments - user on October 1, 2024: \\"오늘 다녀온 스타벅스 강남R점\\".">
                    </head><body></body></html>
                    """;
            when(scraperClient.fetchHtml(anyString(), any(ChatbotContext.class)))
                    .thenReturn(Optional.of(html));
            when(captionCleaner.clean(anyString())).thenReturn("오늘 다녀온 스타벅스 강남R점");
            when(geminiPlaceClient.extractPlaceName(anyString(), nullable(Long.class)))
                    .thenReturn(Optional.of("스타벅스 강남R점"));

            // act
            Optional<InstagramExtraction> result = instagramContentService.extract(
                    "https://www.instagram.com/p/ABC/", ctx
            );

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().placeKeyword()).isEqualTo("스타벅스 강남R점");
            assertThat(result.get().captionSnippet()).isEqualTo("오늘 다녀온 스타벅스 강남R점");
            verify(scraperClient).fetchHtml(anyString(), any(ChatbotContext.class));
            verify(geminiPlaceClient).extractPlaceName(anyString(), nullable(Long.class));
        }

        @DisplayName("ctx 가 이미 만료되었으면 (deadlineMs=0), CoreException 을 throw 하고 Gemini 는 호출되지 않는다.")
        @Test
        void extract_ctxExpired_throwsAndSkipsGemini() {
            // arrange
            when(placeProperties.instagram()).thenReturn(instagram(true));
            instagramContentService = new InstagramContentService(
                    placeProperties, scraperClient, captionCleaner, geminiPlaceClient);
            ChatbotContext ctx = ChatbotContext.start(0L);

            // act & assert
            assertThatThrownBy(() -> instagramContentService.extract(
                    "https://www.instagram.com/p/ABC/", ctx
            ))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PLC_INSTAGRAM_SCRAPE_FAILED);
            verify(geminiPlaceClient, never()).extractPlaceName(anyString(), nullable(Long.class));
        }

        @DisplayName("Gemini 가 Empty 를 반환하면 (타임아웃/429/응답=null), InstagramContentService 도 Empty 를 반환한다 (AC-7/AC-10).")
        @Test
        void extract_geminiReturnsEmpty_returnsEmpty() {
            // arrange
            when(placeProperties.instagram()).thenReturn(instagram(true));
            instagramContentService = new InstagramContentService(
                    placeProperties, scraperClient, captionCleaner, geminiPlaceClient);
            ChatbotContext ctx = ChatbotContext.start(5_000L);
            String html = """
                    <html><head>
                    <meta property="og:description" content="자유로운 일상 캡션">
                    </head><body></body></html>
                    """;
            when(scraperClient.fetchHtml(anyString(), any(ChatbotContext.class)))
                    .thenReturn(Optional.of(html));
            when(captionCleaner.clean(anyString())).thenReturn("자유로운 일상 캡션");
            when(geminiPlaceClient.extractPlaceName(anyString(), nullable(Long.class))).thenReturn(Optional.empty());

            // act
            Optional<InstagramExtraction> result = instagramContentService.extract(
                    "https://www.instagram.com/p/ABC/", ctx
            );

            // assert
            assertThat(result).isEmpty();
            verify(geminiPlaceClient).extractPlaceName(anyString(), nullable(Long.class));
        }
    }
}
