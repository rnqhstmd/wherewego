package com.wherewego.domain.chatbot.handler;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.PendingInstagramAutoSaveScheduler;
import com.wherewego.domain.chatbot.PendingInstagramSession;
import com.wherewego.domain.chatbot.PendingNotificationSession;
import com.wherewego.domain.chatbot.RecentlyAutoSaved;
import com.wherewego.domain.chatbot.RecentlyAutoSavedSession;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.RegisterPinResult;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.domain.place.PlaceCandidate;
import com.wherewego.domain.place.PlaceFallbackOrchestrator;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.PlaceSearchOutcome;
import com.wherewego.domain.place.PlaceSearchService;
import com.wherewego.domain.place.PlaceSelectionCandidateStore;
import com.wherewego.domain.place.parser.ContentParser;
import com.wherewego.domain.place.parser.ContentParserRegistry;
import com.wherewego.domain.place.parser.ParsedContent;
import com.wherewego.infrastructure.chatbot.callback.KakaoCallbackClient;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InstagramLinkHandler} in-batch dedup + 메모 표시 + RESEND-1 가드 단위 테스트.
 *
 * <p>본 핸들러는 외부 의존이 많아 풀 Spring 컨텍스트로 띄우면 비용이 크다.
 * Mockito 단위로 직접 wire하여 다음 시나리오만 정밀 검증한다:</p>
 *
 * <ul>
 *   <li>(a) DUP-1 stage 1 — candidates 이름 중복</li>
 *   <li>(b) DUP-1 stage 2 — placeSearchService가 같은 placeName/좌표 hit 반환</li>
 *   <li>(c) 동명 다른 좌표 (스벅 강남/역삼) — 둘 다 응답</li>
 *   <li>(d) MEMO-1 alreadySaved-only + 메모 → 메모 + ℹ️ 안내</li>
 *   <li>(e) MEMO-1 autoRegistered 있음 + 메모 → 메모 1번만</li>
 *   <li>(f) RESEND-1 처음 hit 후 같은 URL 재전송 → 안내 응답</li>
 *   <li>(g) RESEND 시 PendingNotificationSession.invalidate 호출</li>
 *   <li>(h) RESEND vs D — RESEND가 먼저, pending put 호출 안 됨</li>
 *   <li>(j) 빈 body fallback — peek.body 비어있으면 fallback 문구</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstagramLinkHandlerBatchDedupTest {

    private static final String BOT_USER_KEY = "bot-1";
    private static final Long USER_ID = 100L;
    private static final Long GROUP_ID = 200L;
    private static final String URL_A = "https://www.instagram.com/p/AAA/";
    private static final String URL_B = "https://www.instagram.com/p/BBB/";

    @Mock private BotUserMappingService botUserMappingService;
    @Mock private GroupMemberService groupMemberService;
    @Mock private ContentParserRegistry contentParserRegistry;
    @Mock private PlaceSearchService placeSearchService;
    @Mock private PinService pinService;
    @Mock private PlaceSelectionCandidateStore placeSelectionCandidateStore;
    @Mock private TwoSecondMemoSession twoSecondMemoSession;
    @Mock private PlaceFallbackOrchestrator placeFallbackOrchestrator;
    @Mock private PlaceProperties placeProperties;
    @Mock private KakaoCallbackClient kakaoCallbackClient;
    @Mock private PendingInstagramSession pendingInstagramSession;
    @Mock private PendingInstagramAutoSaveScheduler autoSaveScheduler;
    @Mock private PendingNotificationSession pendingNotificationSession;
    @Mock private RecentlyAutoSavedSession recentlyAutoSavedSession;
    @Mock private NotificationService notificationService;
    @Mock private ContentParser parser;

    private InstagramLinkHandler handler;

    @BeforeEach
    void setUp() {
        handler = new InstagramLinkHandler(
                botUserMappingService, groupMemberService, contentParserRegistry,
                placeSearchService, pinService, placeSelectionCandidateStore,
                twoSecondMemoSession, placeFallbackOrchestrator, placeProperties,
                kakaoCallbackClient, pendingInstagramSession, autoSaveScheduler,
                pendingNotificationSession, recentlyAutoSavedSession,
                notificationService, 60L);
    }

    // ============================== DUP-1 ==============================

    @Test
    @DisplayName("(a) DUP-1 stage 1 — 같은 cand.name() 2번이면 1번만 처리")
    void dup1_stage1_sameName_dedup() {
        PlaceCandidate dup = candidateOf("시장정육점식당");
        stubParser(List.of(dup, dup));

        PlaceSearchHit hit = hitOf("시장정육점식당", "서울", 37.5000, 127.0000, "kakao-1");
        when(placeSearchService.searchByKeyword(eq("시장정육점식당"), any()))
                .thenReturn(new PlaceSearchOutcome.Single(hit));
        when(pinService.registerFromInstagramWithDedup(any(), any(), any(), any(), any()))
                .thenReturn(new RegisterPinResult(pin("시장정육점식당"), true));

        ChatbotV1Dto.SkillResponse resp = handler.processWithMemoAsync(
                BOT_USER_KEY, USER_ID, GROUP_ID, URL_A, null, null);

        String text = extractText(resp);
        // 1차 가드에 의해 placeSearchService는 1번만 호출되고 응답에도 1번
        verify(placeSearchService).searchByKeyword(eq("시장정육점식당"), any());
        assertThat(countOccurrences(text, "• 시장정육점식당")).isEqualTo(1);
    }

    @Test
    @DisplayName("(b) DUP-1 stage 2 — 다른 cand.name()이지만 같은 placeName+좌표 hit이면 1번만 처리")
    void dup1_stage2_sameHit_dedup() {
        PlaceCandidate a = candidateOf("시장정육점식당");
        PlaceCandidate b = candidateOf("시장정육점");
        stubParser(List.of(a, b));

        PlaceSearchHit sameHit = hitOf("시장정육점식당", "서울", 37.5000, 127.0000, "kakao-1");
        when(placeSearchService.searchByKeyword(any(), any()))
                .thenReturn(new PlaceSearchOutcome.Single(sameHit));
        when(pinService.registerFromInstagramWithDedup(any(), any(), any(), any(), any()))
                .thenReturn(new RegisterPinResult(pin("시장정육점식당"), true));

        ChatbotV1Dto.SkillResponse resp = handler.processWithMemoAsync(
                BOT_USER_KEY, USER_ID, GROUP_ID, URL_A, null, null);

        String text = extractText(resp);
        // pinService는 1번만 호출 (2차 가드에 의해 2번째 cand의 같은 hit는 차단)
        verify(pinService).registerFromInstagramWithDedup(any(), any(), any(), any(), any());
        assertThat(countOccurrences(text, "• 시장정육점식당")).isEqualTo(1);
    }

    @Test
    @DisplayName("(c) 동명 다른 좌표 (스벅 강남/역삼) — 둘 다 응답에 노출 (LinkedHashSet 안전망 회귀 방지)")
    void dup1_sameName_differentCoord_keepsBoth() {
        PlaceCandidate cand1 = candidateOf("스타벅스 강남");
        PlaceCandidate cand2 = candidateOf("스타벅스 역삼");
        stubParser(List.of(cand1, cand2));

        PlaceSearchHit hit1 = hitOf("스타벅스", "강남", 37.4980, 127.0270, "k1");
        PlaceSearchHit hit2 = hitOf("스타벅스", "역삼", 37.5000, 127.0360, "k2");
        when(placeSearchService.searchByKeyword(eq("스타벅스 강남"), any()))
                .thenReturn(new PlaceSearchOutcome.Single(hit1));
        when(placeSearchService.searchByKeyword(eq("스타벅스 역삼"), any()))
                .thenReturn(new PlaceSearchOutcome.Single(hit2));
        when(pinService.registerFromInstagramWithDedup(any(), any(), eq(hit1), any(), any()))
                .thenReturn(new RegisterPinResult(pin("스타벅스 강남점"), false));
        when(pinService.registerFromInstagramWithDedup(any(), any(), eq(hit2), any(), any()))
                .thenReturn(new RegisterPinResult(pin("스타벅스 역삼점"), false));

        ChatbotV1Dto.SkillResponse resp = handler.processWithMemoAsync(
                BOT_USER_KEY, USER_ID, GROUP_ID, URL_A, null, null);

        String text = extractText(resp);
        assertThat(text).contains("스타벅스 강남점", "스타벅스 역삼점");
    }

    // ============================== MEMO-1 ==============================

    @Test
    @DisplayName("(d) MEMO-1 alreadySaved-only + 메모 → 📝 메모 + ℹ️ 안내")
    void memo1_alreadySavedOnly_withMemo_showsMemoAndGuide() {
        PlaceCandidate cand = candidateOf("피탕김탕");
        stubParser(List.of(cand));

        PlaceSearchHit hit = hitOf("피탕김탕", "서울", 37.5, 127.0, "k");
        when(placeSearchService.searchByKeyword(any(), any()))
                .thenReturn(new PlaceSearchOutcome.Single(hit));
        when(pinService.registerFromInstagramWithDedup(any(), any(), any(), any(), any()))
                .thenReturn(new RegisterPinResult(pin("피탕김탕"), true));

        ChatbotV1Dto.SkillResponse resp = handler.processWithMemoAsync(
                BOT_USER_KEY, USER_ID, GROUP_ID, URL_A,
                "피탕김탕은 중복이지만 나머진 아님", null);

        String text = extractText(resp);
        assertThat(text)
                .contains("📌 이미 저장된 장소")
                .contains("• 피탕김탕")
                .contains("📝 메모: 피탕김탕은 중복이지만 나머진 아님")
                .contains("ℹ️ 앱에서 이 장소들의 메모를 직접 추가·수정할 수 있어요");
    }

    @Test
    @DisplayName("(e) MEMO-1 autoRegistered + 메모 → 메모 1번만, ℹ️ 안내 없음")
    void memo1_autoRegistered_withMemo_singleMemoLine() {
        PlaceCandidate cand = candidateOf("새 카페");
        stubParser(List.of(cand));

        PlaceSearchHit hit = hitOf("새 카페", "서울", 37.5, 127.0, "k");
        when(placeSearchService.searchByKeyword(any(), any()))
                .thenReturn(new PlaceSearchOutcome.Single(hit));
        when(pinService.registerFromInstagramWithDedup(any(), any(), any(), any(), any()))
                .thenReturn(new RegisterPinResult(pin("새 카페"), false));

        ChatbotV1Dto.SkillResponse resp = handler.processWithMemoAsync(
                BOT_USER_KEY, USER_ID, GROUP_ID, URL_A, "맛있어보임", null);

        String text = extractText(resp);
        assertThat(countOccurrences(text, "📝 메모: 맛있어보임")).isEqualTo(1);
        assertThat(text).doesNotContain("ℹ️");
    }

    // ============================== RESEND-1 ==============================

    @Test
    @DisplayName("(f) RESEND-1 — peek hit이면 안내 응답 + pending put 호출 안 됨")
    void resend1_peekHit_returnsGuidance_andSkipsPending() {
        when(recentlyAutoSavedSession.peek(BOT_USER_KEY, URL_A))
                .thenReturn(Optional.of(new RecentlyAutoSaved(
                        URL_A, "✅ 장소 1개가 저장되었어요\n• 새 카페", Instant.now())));
        stubBotMapping();

        ChatbotV1Dto.SkillResponse resp = handler.handle(skillRequest(URL_A), ChatbotContext.start(5000L));

        String text = extractText(resp);
        assertThat(text)
                .startsWith("📌 이 링크는 이미 다음 장소로 자동 저장되었어요")
                .contains("• 새 카페")
                .contains("ℹ️ 앱에서 메모와 태그를 직접 추가·수정할 수 있어요");
        verify(pendingInstagramSession, never()).put(any(), any());
        verify(autoSaveScheduler, never()).schedule(any(), anyLong(), any());
    }

    @Test
    @DisplayName("(g) RESEND-1 시 PendingNotificationSession.invalidate 호출 (prefix 중첩 방지)")
    void resend1_invalidatesPendingNotification() {
        when(recentlyAutoSavedSession.peek(BOT_USER_KEY, URL_A))
                .thenReturn(Optional.of(new RecentlyAutoSaved(URL_A, "body", Instant.now())));
        stubBotMapping();

        handler.handle(skillRequest(URL_A), ChatbotContext.start(5000L));

        verify(pendingNotificationSession).invalidate(BOT_USER_KEY);
    }

    @Test
    @DisplayName("(h) RESEND가 D 시나리오보다 우선 — pending(B) 있어도 같은 URL_A 재전송이면 RESEND만 동작")
    void resend1_takesPrecedenceOverScenarioD() {
        when(recentlyAutoSavedSession.peek(BOT_USER_KEY, URL_A))
                .thenReturn(Optional.of(new RecentlyAutoSaved(URL_A, "body", Instant.now())));
        // 동시에 pending(B)도 있다고 가정
        when(pendingInstagramSession.peek(BOT_USER_KEY)).thenReturn(Optional.of(URL_B));
        stubBotMapping();

        ChatbotV1Dto.SkillResponse resp = handler.handle(skillRequest(URL_A), ChatbotContext.start(5000L));

        String text = extractText(resp);
        assertThat(text).startsWith("📌 이 링크는 이미 다음 장소로 자동 저장되었어요");
        // D 시나리오 트리거 안 됨: pending 덮어쓰기 없음
        verify(pendingInstagramSession, never()).put(any(), any());
        // autoSavePreviousImmediately로 가는 schedule 없음
        verify(autoSaveScheduler, never()).schedule(any(), anyLong(), any());
    }

    @Test
    @DisplayName("(j) 빈 body fallback — peek.body 비어있어도 RESEND 동작, fallback 문구 표시")
    void resend1_emptyBody_fallbackMessage() {
        when(recentlyAutoSavedSession.peek(BOT_USER_KEY, URL_A))
                .thenReturn(Optional.of(new RecentlyAutoSaved(URL_A, "", Instant.now())));
        stubBotMapping();

        ChatbotV1Dto.SkillResponse resp = handler.handle(skillRequest(URL_A), ChatbotContext.start(5000L));

        String text = extractText(resp);
        assertThat(text).contains("(저장 본문을 다시 표시할 수 없어요)");
    }

    // ============================== helpers ==============================

    private static PlaceCandidate candidateOf(String name) {
        return new PlaceCandidate(name, true);
    }

    private static PlaceSearchHit hitOf(String placeName, String address, double lat, double lng, String kakaoPlaceId) {
        return new PlaceSearchHit(kakaoPlaceId, placeName, address, lat, lng);
    }

    private void stubParser(List<PlaceCandidate> candidates) {
        when(contentParserRegistry.resolve(any())).thenReturn(Optional.of(parser));
        ParsedContent parsed = new ParsedContent(
                candidates.isEmpty() ? "" : candidates.get(0).name(),
                "",
                List.of(),
                candidates);
        try {
            when(parser.parse(any(), any())).thenReturn(Optional.of(parsed));
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubBotMapping() {
        // ctx.userId() == null인 경우만 호출됨. ChatbotContext.start()는 userId 미설정.
        when(botUserMappingService.resolveUserId(BOT_USER_KEY)).thenReturn(Optional.of(USER_ID));
        when(groupMemberService.findLatestActiveGroupIdByUserId(USER_ID))
                .thenReturn(Optional.of(GROUP_ID));
        lenient().when(contentParserRegistry.resolve(any())).thenReturn(Optional.of(parser));
    }

    private ChatbotV1Dto.SkillRequest skillRequest(String url) {
        return new ChatbotV1Dto.SkillRequest(
                new ChatbotV1Dto.UserRequest(url,
                        new ChatbotV1Dto.User(BOT_USER_KEY, "botUserKey"),
                        null),
                null);
    }

    private Pin pin(String placeName) {
        return Pin.autoFromInstagram(GROUP_ID, USER_ID,
                hitOf(placeName, "address", 37.5, 127.0, "k"),
                URL_A);
    }

    private static String extractText(ChatbotV1Dto.SkillResponse resp) {
        if (resp == null || resp.template() == null || resp.template().outputs() == null
                || resp.template().outputs().isEmpty()) {
            return "";
        }
        Map<String, Object> first = resp.template().outputs().get(0);
        Object st = first.get("simpleText");
        if (st instanceof Map) {
            Object text = ((Map<?, ?>) st).get("text");
            if (text instanceof String s) return s;
        }
        return "";
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
