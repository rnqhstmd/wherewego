package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.scraper.instagram.InstagramScraperClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private InstagramContentService instagramContentService;

    private static PlaceProperties.Instagram instagram(boolean scrapingEnabled) {
        return new PlaceProperties.Instagram(scrapingEnabled);
    }

    @DisplayName("인스타그램 URL 에서 장소를 추출할 때,")
    @Nested
    class Extract {

        @DisplayName("scraping-enabled=false 면, 즉시 Empty 를 반환하고 scraperClient 를 호출하지 않는다 (AC-18).")
        @Test
        void extract_scrapingDisabled_returnsEmpty() {
            // arrange
            when(placeProperties.instagram()).thenReturn(instagram(false));
            instagramContentService = new InstagramContentService(placeProperties, scraperClient);
            ChatbotContext ctx = ChatbotContext.start(5_000L);

            // act
            Optional<InstagramExtraction> result = instagramContentService.extract(
                    "https://www.instagram.com/p/ABC/", ctx
            );

            // assert
            assertThat(result).isEmpty();
            verify(scraperClient, never()).fetchHtml(anyString(), any(ChatbotContext.class));
        }

        @DisplayName("scraping-enabled=true 이고 정상 HTML 이면, InstagramExtraction 을 반환한다.")
        @Test
        void extract_scrapingEnabled_returnsExtraction() {
            // arrange
            when(placeProperties.instagram()).thenReturn(instagram(true));
            instagramContentService = new InstagramContentService(placeProperties, scraperClient);
            ChatbotContext ctx = ChatbotContext.start(5_000L);
            String html = """
                    <html><head>
                    <meta property="og:title" content="Sample post">
                    <meta property="og:description" content="📍 스타벅스 강남R점 #카페 #오늘다녀온곳">
                    </head><body></body></html>
                    """;
            when(scraperClient.fetchHtml(anyString(), any(ChatbotContext.class)))
                    .thenReturn(Optional.of(html));

            // act
            Optional<InstagramExtraction> result = instagramContentService.extract(
                    "https://www.instagram.com/p/ABC/", ctx
            );

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().placeKeyword()).isEqualTo("스타벅스 강남R점");
            assertThat(result.get().captionSnippet()).contains("📍");
            verify(scraperClient).fetchHtml(anyString(), any(ChatbotContext.class));
        }
    }
}
