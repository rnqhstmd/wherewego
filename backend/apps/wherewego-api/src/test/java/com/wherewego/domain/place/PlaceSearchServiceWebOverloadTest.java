package com.wherewego.domain.place;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.infrastructure.place.google.GooglePlacesClient;
import com.wherewego.infrastructure.place.kakao.KakaoLocalClient;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlaceSearchService#searchByKeyword(String)} 웹 오버로드 단위 테스트 (Phase 6 §B2).
 *
 * <p>카카오 우선 → 0건/실패 시 Google 동기 폴백 → 데드라인 보호 분기를 단독 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceSearchServiceWebOverloadTest {

    private static final String KEYWORD = "성수동 카페";

    @Mock
    private KakaoLocalClient kakaoLocalClient;

    @Mock
    private GooglePlacesClient googlePlacesClient;

    @Mock
    private PlaceProperties placeProperties;

    @Mock
    private PlaceProperties.Search searchProperties;

    private PlaceSearchService service;

    @BeforeEach
    void setUp() {
        when(placeProperties.search()).thenReturn(searchProperties);
        when(searchProperties.kakaoLocalSize()).thenReturn(5);
        service = new PlaceSearchService(kakaoLocalClient, googlePlacesClient, placeProperties);
    }

    private static PlaceSearchHit hit(String id, String name) {
        return new PlaceSearchHit(id, name, "주소-" + id, 37.5, 127.0);
    }

    @DisplayName("카카오가 결과를 반환하면 Google 폴백 없이 카카오 결과로 Outcome 을 만든다.")
    @Test
    void searchByKeyword_kakaoHasResults_returnsKakao() {
        // arrange
        PlaceSearchHit h1 = hit("k1", "카페1");
        PlaceSearchHit h2 = hit("k2", "카페2");
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of(h1, h2));

        // act
        PlaceSearchOutcome outcome = service.searchByKeyword(KEYWORD);

        // assert
        assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Multiple.class);
        assertThat(((PlaceSearchOutcome.Multiple) outcome).hits()).containsExactly(h1, h2);
        verify(googlePlacesClient, never()).searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class));
    }

    @DisplayName("카카오가 0건이면 Google 폴백을 호출하여 결과를 반환한다.")
    @Test
    void searchByKeyword_kakaoEmpty_callsGoogle() {
        // arrange
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of());
        PlaceSearchHit g = hit("g1", "Fallback");
        when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of(g));

        // act
        PlaceSearchOutcome outcome = service.searchByKeyword(KEYWORD);

        // assert
        assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Single.class);
        assertThat(((PlaceSearchOutcome.Single) outcome).hit()).isEqualTo(g);
        verify(googlePlacesClient).searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class));
    }

    @DisplayName("카카오가 CoreException 을 던지면 Google 폴백을 호출한다.")
    @Test
    void searchByKeyword_kakaoFail_callsGoogle() {
        // arrange
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenThrow(new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED));
        PlaceSearchHit g = hit("g1", "Fallback");
        when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenReturn(List.of(g));

        // act
        PlaceSearchOutcome outcome = service.searchByKeyword(KEYWORD);

        // assert
        assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Single.class);
        assertThat(((PlaceSearchOutcome.Single) outcome).hit()).isEqualTo(g);
        verify(googlePlacesClient).searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class));
    }

    @DisplayName("카카오 호출 소요로 잔여 데드라인이 부족하면 Google 폴백을 생략하고 Empty 를 반환한다.")
    @Test
    void searchByKeyword_deadlineExceeded_skipsGoogle() {
        // arrange : 카카오가 3500ms 이상 소요되도록 sleep 시뮬레이션
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(3_200);
                    return List.of();
                });

        // act
        PlaceSearchOutcome outcome = service.searchByKeyword(KEYWORD);

        // assert : Google 폴백 미호출 + Empty 반환
        assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Empty.class);
        verify(googlePlacesClient, never()).searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class));
    }

    @DisplayName("카카오와 Google 모두 실패하면 Empty 를 반환한다 (502 던지지 않음).")
    @Test
    void searchByKeyword_bothFail_returnsEmpty() {
        // arrange
        when(kakaoLocalClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenThrow(new CoreException(ErrorType.PLC_KAKAO_LOCAL_FAILED));
        when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                .thenThrow(new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED));

        // act
        PlaceSearchOutcome outcome = service.searchByKeyword(KEYWORD);

        // assert
        assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Empty.class);
    }
}
