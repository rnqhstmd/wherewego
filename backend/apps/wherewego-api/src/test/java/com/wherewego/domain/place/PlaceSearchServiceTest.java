package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.place.kakao.KakaoLocalClient;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceSearchServiceTest {

    @Mock
    private KakaoLocalClient kakaoLocalClient;

    @Mock
    private PlaceProperties placeProperties;

    private PlaceSearchService placeSearchService;

    @BeforeEach
    void setUp() {
        PlaceProperties.Search search = new PlaceProperties.Search(5_000L, 5, 1_700L);
        when(placeProperties.search()).thenReturn(search);
        placeSearchService = new PlaceSearchService(kakaoLocalClient, placeProperties);
    }

    private static PlaceSearchHit hit(String id, String name) {
        return new PlaceSearchHit(id, name, "주소", 37.0, 127.0);
    }

    @DisplayName("키워드로 장소를 검색할 때,")
    @Nested
    class SearchByKeyword {

        @DisplayName("결과가 1건이면, Single 을 반환한다 (AC-9).")
        @Test
        void searchByKeyword_singleHit_returnsSingle() {
            // arrange
            ChatbotContext ctx = ChatbotContext.start(5_000L);
            PlaceSearchHit h = hit("k1", "스타벅스 강남R점");
            when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(List.of(h));

            // act
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword("스타벅스 강남", ctx);

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Single.class);
            assertThat(((PlaceSearchOutcome.Single) outcome).hit()).isEqualTo(h);
        }

        @DisplayName("결과가 5건을 초과해도, 최대 5건만 잘라서 Multiple 을 반환한다 (AC-10).")
        @Test
        void searchByKeyword_multipleHits_returnsMultipleAtMost5() {
            // arrange
            ChatbotContext ctx = ChatbotContext.start(5_000L);
            List<PlaceSearchHit> seven = IntStream.range(0, 7)
                    .mapToObj(i -> hit("k" + i, "장소-" + i))
                    .toList();
            when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(seven);

            // act
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword("키워드", ctx);

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Multiple.class);
            assertThat(((PlaceSearchOutcome.Multiple) outcome).hits()).hasSize(5);
        }

        @DisplayName("결과가 비어있으면, Empty 를 반환한다 (AC-12).")
        @Test
        void searchByKeyword_emptyHits_returnsEmpty() {
            // arrange
            ChatbotContext ctx = ChatbotContext.start(5_000L);
            when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(List.of());

            // act
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword("키워드", ctx);

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Empty.class);
        }

        @DisplayName("클라이언트가 CoreException 을 던지면, Empty 폴백을 반환한다.")
        @Test
        void searchByKeyword_clientException_returnsEmpty() {
            // arrange
            ChatbotContext ctx = ChatbotContext.start(5_000L);
            when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenThrow(new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED));

            // act
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword("키워드", ctx);

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Empty.class);
        }
    }
}
