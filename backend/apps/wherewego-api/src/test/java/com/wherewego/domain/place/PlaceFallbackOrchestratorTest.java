package com.wherewego.domain.place;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.FallbackJobContext;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.RegisterPinResult;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.infrastructure.chatbot.callback.KakaoCallbackClient;
import com.wherewego.infrastructure.notify.slack.SlackNotifier;
import com.wherewego.infrastructure.place.google.GooglePlacesClient;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceFallbackOrchestratorTest {

    private static final String KEYWORD = "도쿄 라멘";
    private static final String BOT_USER_KEY = "botUserKey";
    private static final String CALLBACK_URL = "https://cb.example/abc";
    private static final String INSTAGRAM_URL = "https://insta.com/p/abc";
    private static final long ASYNC_TIMEOUT_MS = 2_000L;

    @Mock
    private GooglePlacesClient googlePlacesClient;

    @Mock
    private KakaoCallbackClient kakaoCallbackClient;

    @Mock
    private SlackNotifier slackNotifier;

    @Mock
    private PinService pinService;

    @Mock
    private PlaceSelectionCandidateStore candidateStore;

    @Mock
    private TwoSecondMemoSession twoSecondMemoSession;

    @Mock
    private Pin savedPin;

    @Mock
    private Pin existingPin;

    private PlaceFallbackOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new PlaceFallbackOrchestrator(
                googlePlacesClient,
                kakaoCallbackClient,
                slackNotifier,
                pinService,
                candidateStore,
                twoSecondMemoSession
        );
    }

    private static PlaceSearchHit hit(String id, String name) {
        return new PlaceSearchHit(id, name, "주소-" + id, 37.5, 127.0);
    }

    private static FallbackJobContext jobCtx() {
        return new FallbackJobContext(
                BOT_USER_KEY, 1L, 2L, CALLBACK_URL,
                INSTAGRAM_URL, KEYWORD
        );
    }

    private static ChatbotContext freshCtx() {
        return ChatbotContext.start(3_000L);
    }

    @DisplayName("runSync 를 호출할 때,")
    @Nested
    class RunSync {

        @DisplayName("Google 이 1건을 반환하면, Single 을 반환한다.")
        @Test
        void runSync_singleHit_returnsSingle() {
            // arrange
            PlaceSearchHit h = hit("g1", "장소-단건");
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(List.of(h));

            // act
            PlaceSearchOutcome outcome = orchestrator.runSync(KEYWORD, freshCtx());

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Single.class);
            assertThat(((PlaceSearchOutcome.Single) outcome).hit()).isEqualTo(h);
        }

        @DisplayName("Google 이 비어있으면, Empty 를 반환한다.")
        @Test
        void runSync_emptyHits_returnsEmpty() {
            // arrange
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(List.of());

            // act
            PlaceSearchOutcome outcome = orchestrator.runSync(KEYWORD, freshCtx());

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Empty.class);
        }

        @DisplayName("Google 이 2건 이상을 반환하면, Multiple 을 반환한다.")
        @Test
        void runSync_multipleHits_returnsMultiple() {
            // arrange
            List<PlaceSearchHit> three = List.of(
                    hit("g1", "장소-1"),
                    hit("g2", "장소-2"),
                    hit("g3", "장소-3")
            );
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(three);

            // act
            PlaceSearchOutcome outcome = orchestrator.runSync(KEYWORD, freshCtx());

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Multiple.class);
            assertThat(((PlaceSearchOutcome.Multiple) outcome).hits()).hasSize(3);
        }

        @DisplayName("Google 이 CoreException 을 던지면, Slack 알림 후 Empty 를 반환한다.")
        @Test
        void runSync_googleFailure_notifiesSlackAndReturnsEmpty() {
            // arrange
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenThrow(new CoreException(ErrorType.PLC_GOOGLE_PLACES_FAILED));

            // act
            PlaceSearchOutcome outcome = orchestrator.runSync(KEYWORD, freshCtx());

            // assert
            assertThat(outcome).isInstanceOf(PlaceSearchOutcome.Empty.class);
            verify(slackNotifier).notifyFailure(eq("Google Places sync fallback failed"), any(Map.class));
        }
    }

    @DisplayName("runAsync 를 호출할 때,")
    @Nested
    class RunAsync {

        @DisplayName("Single 결과면, pinService.register 후 콜백으로 저장 메시지를 push 한다.")
        @Test
        void runAsync_singleHit_registersPinAndPushesText() {
            // arrange
            PlaceSearchHit h = hit("g1", "도쿄라멘본점");
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(List.of(h));
            when(pinService.registerFromInstagramWithDedup(
                    anyLong(), anyLong(), any(PlaceSearchHit.class), anyString(), isNull()))
                    .thenReturn(new RegisterPinResult(savedPin, false));
            when(savedPin.getId()).thenReturn(42L);
            when(savedPin.getPlaceName()).thenReturn("도쿄라멘본점");

            // act
            orchestrator.runAsync(KEYWORD, jobCtx());

            // assert
            verify(pinService, timeout(ASYNC_TIMEOUT_MS))
                    .registerFromInstagramWithDedup(eq(1L), eq(2L), eq(h), eq(INSTAGRAM_URL), isNull());
            verify(twoSecondMemoSession, timeout(ASYNC_TIMEOUT_MS))
                    .put(eq(BOT_USER_KEY), eq(42L));
            verify(kakaoCallbackClient, timeout(ASYNC_TIMEOUT_MS))
                    .pushText(eq(CALLBACK_URL), eq("장소가 저장되었어요: 도쿄라멘본점"));
        }

        @DisplayName("이미 저장된 핀이면, 콜백으로 '📌 이미 저장된 장소' 메시지를 push 한다 (Slack 미알림).")
        @Test
        void runAsync_duplicatePin_pushesAlreadySavedTextAndNoSlack() {
            // arrange
            PlaceSearchHit h = hit("g1", "중복장소");
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(List.of(h));
            when(existingPin.getPlaceName()).thenReturn("중복장소");
            when(pinService.registerFromInstagramWithDedup(
                    anyLong(), anyLong(), any(PlaceSearchHit.class), anyString(), isNull()))
                    .thenReturn(new RegisterPinResult(existingPin, true));

            // act
            orchestrator.runAsync(KEYWORD, jobCtx());

            // assert
            verify(kakaoCallbackClient, timeout(ASYNC_TIMEOUT_MS))
                    .pushText(eq(CALLBACK_URL), eq("📌 이미 저장된 장소\n• 중복장소"));
            verify(twoSecondMemoSession, never()).put(anyString(), anyLong());
            verify(slackNotifier, never()).notifyFailure(anyString(), any(Map.class));
        }

        @DisplayName("Multiple 결과면, candidateStore.put 과 BasicCard 콜백 push 가 발생한다.")
        @Test
        void runAsync_multipleHits_putsCandidatesAndPushesCard() {
            // arrange
            List<PlaceSearchHit> two = List.of(
                    hit("g1", "장소-A"),
                    hit("g2", "장소-B")
            );
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(two);

            // act
            orchestrator.runAsync(KEYWORD, jobCtx());

            // assert
            verify(candidateStore, timeout(ASYNC_TIMEOUT_MS))
                    .put(eq(BOT_USER_KEY), eq("g1"), any(PlaceSelectionCandidateStore.Entry.class));
            verify(candidateStore, timeout(ASYNC_TIMEOUT_MS))
                    .put(eq(BOT_USER_KEY), eq("g2"), any(PlaceSelectionCandidateStore.Entry.class));
            verify(kakaoCallbackClient, timeout(ASYNC_TIMEOUT_MS))
                    .push(eq(CALLBACK_URL), any(ChatbotV1Dto.SkillResponse.class));
        }

        @DisplayName("Empty 결과면, 콜백으로 '장소를 찾을 수 없습니다' 메시지를 push 한다.")
        @Test
        void runAsync_emptyHits_pushesNotFoundText() {
            // arrange
            when(googlePlacesClient.searchByKeyword(anyString(), anyInt(), any(ChatbotContext.class)))
                    .thenReturn(List.of());

            // act
            orchestrator.runAsync(KEYWORD, jobCtx());

            // assert
            verify(kakaoCallbackClient, timeout(ASYNC_TIMEOUT_MS))
                    .pushText(eq(CALLBACK_URL), eq("장소를 찾을 수 없습니다."));
        }
    }
}
