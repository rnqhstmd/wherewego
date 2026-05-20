package com.wherewego.domain.chatbot.handler;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.ChatbotErrorMessages;
import com.wherewego.domain.chatbot.FallbackJobContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.PendingInstagramAutoSaveScheduler;
import com.wherewego.domain.chatbot.PendingInstagramSession;
import com.wherewego.domain.chatbot.PendingNotificationSession;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinService;
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
import com.wherewego.support.error.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 인스타 링크 수신 핸들러 — 메모 입력 분리 흐름 + TTL 자동 저장.
 *
 * <p>시나리오:
 * <ul>
 *   <li><b>A.</b> URL 받음 → pending put + TTL scheduler 등록 + "메모를 보내주세요" 안내 + QuickReply</li>
 *   <li><b>B.</b> TTL 내 사용자가 메모/`메모 없이 저장` 발화 → PendingMemoHandler가 처리</li>
 *   <li><b>C.</b> TTL 만료 → {@link #autoSaveOnExpiry} 호출 → 백그라운드 자동 저장 + 결과를 PendingNotificationSession 적재 → 사용자 다음 발화에서 prepend</li>
 *   <li><b>D.</b> pending 중 새 URL 도착 → 이전 URL은 {@link #autoSavePreviousImmediately}로 즉시 백그라운드 자동 저장 + 새 URL로 새 pending + 새 scheduler + 합산 안내 응답</li>
 * </ul>
 */
@Component
public class InstagramLinkHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(InstagramLinkHandler.class);

    /** 비동기 candidates 처리에 적용하는 deadline (카카오 callback ttl ~1분). */
    private static final long ASYNC_CANDIDATES_DEADLINE_MS = 50_000L;

    /** 자동 저장(백그라운드)에 적용하는 deadline — 카카오 timeout과 무관하므로 더 너그럽게. */
    private static final long AUTO_SAVE_DEADLINE_MS = 60_000L;

    /** PendingNotificationSession에 적재되는 prepend 안내 prefix. 시간 추정 표현 금지. */
    private static final String AUTO_SAVE_NOTICE_PREFIX = "📌 이전에 보낸 링크는 메모 없이 자동 저장되었어요\n";

    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final ContentParserRegistry contentParserRegistry;
    private final PlaceSearchService placeSearchService;
    private final PinService pinService;
    private final PlaceSelectionCandidateStore placeSelectionCandidateStore;
    private final TwoSecondMemoSession twoSecondMemoSession;
    private final PlaceFallbackOrchestrator placeFallbackOrchestrator;
    private final PlaceProperties placeProperties;
    private final KakaoCallbackClient kakaoCallbackClient;
    private final PendingInstagramSession pendingInstagramSession;
    private final PendingInstagramAutoSaveScheduler autoSaveScheduler;
    private final PendingNotificationSession pendingNotificationSession;
    private final long pendingTtlMs;

    /** N개 candidates 비동기 처리용 풀. D 시나리오의 즉시 백그라운드 처리에도 재사용. */
    private final ExecutorService asyncCandidatesExecutor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "instagram-candidates-async");
                t.setDaemon(true);
                return t;
            });

    public InstagramLinkHandler(BotUserMappingService botUserMappingService,
                                GroupMemberService groupMemberService,
                                ContentParserRegistry contentParserRegistry,
                                PlaceSearchService placeSearchService,
                                PinService pinService,
                                PlaceSelectionCandidateStore placeSelectionCandidateStore,
                                TwoSecondMemoSession twoSecondMemoSession,
                                PlaceFallbackOrchestrator placeFallbackOrchestrator,
                                PlaceProperties placeProperties,
                                KakaoCallbackClient kakaoCallbackClient,
                                PendingInstagramSession pendingInstagramSession,
                                PendingInstagramAutoSaveScheduler autoSaveScheduler,
                                PendingNotificationSession pendingNotificationSession,
                                @Value("${chatbot.instagram.pending-ttl-seconds:180}") long pendingTtlSeconds) {
        this.botUserMappingService = botUserMappingService;
        this.groupMemberService = groupMemberService;
        this.contentParserRegistry = contentParserRegistry;
        this.placeSearchService = placeSearchService;
        this.pinService = pinService;
        this.placeSelectionCandidateStore = placeSelectionCandidateStore;
        this.twoSecondMemoSession = twoSecondMemoSession;
        this.placeFallbackOrchestrator = placeFallbackOrchestrator;
        this.placeProperties = placeProperties;
        this.kakaoCallbackClient = kakaoCallbackClient;
        this.pendingInstagramSession = pendingInstagramSession;
        this.autoSaveScheduler = autoSaveScheduler;
        this.pendingNotificationSession = pendingNotificationSession;
        this.pendingTtlMs = pendingTtlSeconds * 1000L;
    }

    @Override
    public MessageType supports() {
        return MessageType.INSTAGRAM_LINK;
    }

    /**
     * 인스타 URL 수신 시:
     * <ol>
     *   <li>가드 (userId/groupId/parser)</li>
     *   <li>D 시나리오: 이전 pending이 있고 URL이 다르면 이전 URL을 즉시 백그라운드 자동 저장으로 위임</li>
     *   <li>새 URL을 pending put + TTL scheduler 등록</li>
     *   <li>안내 응답 (D 시나리오면 합산 문구, 아니면 일반 문구)</li>
     * </ol>
     */
    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String url = request.userRequest().utterance().trim();

        Long userId = ctx.userId();
        if (userId == null) {
            Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
            if (userIdOpt.isEmpty()) {
                return ChatbotV1Dto.SkillResponse.simple(
                        "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러주세요.");
            }
            userId = userIdOpt.get();
        }

        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("그룹에 먼저 참여해주세요.");
        }

        Optional<ContentParser> parserOpt = contentParserRegistry.resolve(url);
        if (parserOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("지원하지 않는 링크입니다.");
        }

        // D 시나리오 — 이전 pending이 있고 URL이 다르면 즉시 백그라운드 자동 저장 위임.
        Optional<String> prevOpt = pendingInstagramSession.peek(botUserKey);
        boolean dScenario = prevOpt.isPresent() && !prevOpt.get().equals(url);
        if (dScenario) {
            String prevUrl = prevOpt.get();
            // 기존 TTL task는 새 schedule에서 자동 cancel되지만, 명시적으로 한 번 더 cancel.
            autoSaveScheduler.cancel(botUserKey);
            // 이전 URL의 즉시 처리는 별도 executor로 위임 — scheduler는 새 URL TTL에 쓴다.
            asyncCandidatesExecutor.execute(() -> autoSavePreviousImmediately(botUserKey, prevUrl));
        }

        // 새 URL pending 등록 + TTL scheduler 등록.
        pendingInstagramSession.put(botUserKey, url);
        autoSaveScheduler.schedule(botUserKey, pendingTtlMs,
                () -> autoSaveOnExpiry(botUserKey, url));

        return ChatbotV1Dto.SkillResponse.simple(memoPromptText(dScenario), memoQuickReplies());
    }

    /** 안내 문구. dScenario면 이전 링크 백그라운드 저장 안내가 앞에 합쳐진다. */
    private static String memoPromptText(boolean dScenario) {
        if (dScenario) {
            return "이전 링크는 백그라운드로 저장 중이에요.\n\n"
                    + "📝 이번 링크와 함께 저장할 메모를 보내주세요.\n"
                    + "메모 없이 저장하려면 아래 버튼을 눌러주세요.\n"
                    + "(3분 내에 메모를 보내지 않으시면 자동으로 메모 없이 저장돼요)";
        }
        return "📝 이 링크와 함께 저장할 메모를 보내주세요.\n"
                + "메모 없이 저장하려면 아래 버튼을 눌러주세요.\n"
                + "(3분 내에 메모를 보내지 않으시면 자동으로 메모 없이 저장돼요)";
    }

    /** 안내 응답 하단 빠른답장 — 1개. 라벨에 ❌ 이모티콘, 전송값은 "메모 없이 저장" 정확 매칭용. */
    private static List<ChatbotV1Dto.QuickReply> memoQuickReplies() {
        return List.of(
                ChatbotV1Dto.QuickReply.message("❌ 메모 없이 저장", "메모 없이 저장")
        );
    }

    /**
     * 시나리오 C — TTL 만료 시 자동 저장.
     * Scheduler 스레드에서 호출. peek 재확인 후 동일 URL일 때만 처리하여 race 안전.
     */
    void autoSaveOnExpiry(String botUserKey, String instagramUrl) {
        try {
            Optional<String> peeked = pendingInstagramSession.peek(botUserKey);
            if (peeked.isEmpty() || !peeked.get().equals(instagramUrl)) {
                return; // 이미 사용자가 응답했거나 다른 URL로 덮어씀
            }
            String body = runBackgroundAutoSave(botUserKey, instagramUrl);
            if (body != null && !body.isBlank()) {
                pendingNotificationSession.put(botUserKey, AUTO_SAVE_NOTICE_PREFIX + body);
            }
            pendingInstagramSession.invalidate(botUserKey);
        } catch (RuntimeException e) {
            log.error("autoSaveOnExpiry failed url={} cause={}", instagramUrl, e.getMessage(), e);
        }
    }

    /**
     * 시나리오 D — pending 중 새 URL이 들어와 이전 URL을 즉시 백그라운드 저장.
     * pending은 이미 새 URL로 덮어씌워졌으므로 invalidate 안 함.
     */
    void autoSavePreviousImmediately(String botUserKey, String previousUrl) {
        try {
            String body = runBackgroundAutoSave(botUserKey, previousUrl);
            if (body != null && !body.isBlank()) {
                pendingNotificationSession.put(botUserKey, AUTO_SAVE_NOTICE_PREFIX + body);
            }
        } catch (RuntimeException e) {
            log.error("autoSavePreviousImmediately failed url={} cause={}", previousUrl, e.getMessage(), e);
        }
    }

    /** 백그라운드 자동 저장 공통 로직 — userId/groupId/parser 재조회 + candidates 처리 + body 텍스트 추출. */
    private String runBackgroundAutoSave(String botUserKey, String instagramUrl) {
        Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
        if (userIdOpt.isEmpty()) {
            return null;
        }
        Long userId = userIdOpt.get();
        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            return null;
        }
        Optional<ContentParser> parserOpt = contentParserRegistry.resolve(instagramUrl);
        if (parserOpt.isEmpty()) {
            return null;
        }
        ChatbotContext ctx = ChatbotContext.start(AUTO_SAVE_DEADLINE_MS);
        ctx.setUserId(userId);
        ChatbotV1Dto.SkillResponse resp = runParseAndCandidates(
                parserOpt.get(), botUserKey, userId, groupIdOpt.get(), instagramUrl, /*memo*/ null, ctx);
        return extractSimpleText(resp);
    }

    /**
     * 메모 받은 후 candidates 처리 — PendingMemoHandler가 호출.
     * callbackUrl 있으면 useCallback 응답 + 비동기 처리 후 카카오 callback push.
     */
    public ChatbotV1Dto.SkillResponse processWithMemoAsync(String botUserKey,
                                                          Long userId,
                                                          Long groupId,
                                                          String instagramUrl,
                                                          String memo,
                                                          String callbackUrl) {
        Optional<ContentParser> parserOpt = contentParserRegistry.resolve(instagramUrl);
        if (parserOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("지원하지 않는 링크입니다.");
        }

        if (callbackUrl != null && !callbackUrl.isBlank()) {
            try {
                final ContentParser parser = parserOpt.get();
                asyncCandidatesExecutor.execute(() -> {
                    try {
                        ChatbotContext asyncCtx = ChatbotContext.start(ASYNC_CANDIDATES_DEADLINE_MS);
                        asyncCtx.setUserId(userId);
                        ChatbotV1Dto.SkillResponse result = runParseAndCandidates(
                                parser, botUserKey, userId, groupId, instagramUrl, memo, asyncCtx);
                        kakaoCallbackClient.push(callbackUrl, result);
                    } catch (RuntimeException e) {
                        log.error("Async candidates processing failed url={} cause={}",
                                instagramUrl, e.getMessage(), e);
                        kakaoCallbackClient.pushText(callbackUrl,
                                "장소 처리 중 오류가 발생했어요. 다시 시도해 주세요.");
                    }
                });
                return ChatbotV1Dto.SkillResponse.useCallback(
                        "장소를 찾고 있어요. 잠시만 기다려주세요...");
            } catch (RejectedExecutionException e) {
                log.warn("Async candidates queue full, falling through to sync url={}", instagramUrl);
                ChatbotContext syncCtx = ChatbotContext.start(3_500L);
                syncCtx.setUserId(userId);
                return runParseAndCandidates(
                        parserOpt.get(), botUserKey, userId, groupId, instagramUrl, memo, syncCtx);
            }
        }

        // callback 없는 환경 (테스트/시뮬레이터): 동기 처리.
        ChatbotContext syncCtx = ChatbotContext.start(ASYNC_CANDIDATES_DEADLINE_MS);
        syncCtx.setUserId(userId);
        return runParseAndCandidates(
                parserOpt.get(), botUserKey, userId, groupId, instagramUrl, memo, syncCtx);
    }

    /** 인스타 parse → candidates 처리 → 응답 조립. */
    private ChatbotV1Dto.SkillResponse runParseAndCandidates(ContentParser parser,
                                                             String botUserKey,
                                                             Long userId,
                                                             Long groupId,
                                                             String instagramUrl,
                                                             String memo,
                                                             ChatbotContext ctx) {
        Optional<ParsedContent> parsedOpt;
        try {
            parsedOpt = parser.parse(instagramUrl, ctx);
        } catch (CoreException e) {
            log.warn("Instagram parse failed code={}", e.getErrorType().getCode());
            return ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e));
        }
        if (parsedOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾지 못했어요. 앱에서 직접 등록해 주세요.");
        }

        List<PlaceCandidate> candidates = parsedOpt.get().candidates();
        if (candidates.isEmpty()) {
            return handleLegacySingle(parsedOpt.get(), botUserKey, userId, groupId, instagramUrl, memo, ctx);
        }
        return handleCandidates(botUserKey, userId, groupId, instagramUrl, candidates, memo, ctx);
    }

    private ChatbotV1Dto.SkillResponse handleCandidates(String botUserKey,
                                                       Long userId,
                                                       Long groupId,
                                                       String instagramUrl,
                                                       List<PlaceCandidate> candidates,
                                                       String memo,
                                                       ChatbotContext ctx) {
        List<String> autoRegistered = new ArrayList<>();
        List<String> manualNeeded = new ArrayList<>();

        Long lastSavedPinId = null;

        for (int i = 0; i < candidates.size(); i++) {
            PlaceCandidate cand = candidates.get(i);

            if (ctx.expired()) {
                for (int j = i; j < candidates.size(); j++) {
                    manualNeeded.add(candidates.get(j).name());
                }
                log.warn("Chatbot deadline hit, {} remaining → manualNeeded",
                        candidates.size() - i);
                break;
            }

            if (!cand.confident()) {
                manualNeeded.add(cand.name());
                continue;
            }

            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(cand.name(), ctx);

            if (outcome instanceof PlaceSearchOutcome.Empty) {
                manualNeeded.add(cand.name());
                continue;
            }

            if (outcome instanceof PlaceSearchOutcome.Single single) {
                Long savedId = tryRegister(userId, groupId, single.hit(), instagramUrl, memo,
                        autoRegistered, manualNeeded);
                if (savedId != null) lastSavedPinId = savedId;
                continue;
            }

            List<PlaceSearchHit> hits = ((PlaceSearchOutcome.Multiple) outcome).hits();
            Long savedId = tryRegister(userId, groupId, hits.get(0), instagramUrl, memo,
                    autoRegistered, manualNeeded);
            if (savedId != null) lastSavedPinId = savedId;
        }

        if (lastSavedPinId != null) {
            twoSecondMemoSession.put(botUserKey, lastSavedPinId);
        }

        return composeResponse(autoRegistered, manualNeeded, memo);
    }

    private Long tryRegister(Long userId,
                             Long groupId,
                             PlaceSearchHit hit,
                             String instagramUrl,
                             String memo,
                             List<String> autoRegistered,
                             List<String> manualNeeded) {
        try {
            Pin saved = pinService.registerFromInstagram(userId, groupId, hit, instagramUrl, memo);
            autoRegistered.add(saved.getPlaceName());
            return saved.getId();
        } catch (DataIntegrityViolationException e) {
            return null;
        } catch (RuntimeException e) {
            log.warn("registerFromInstagram failed name={} cause={}", hit.placeName(), e.getMessage());
            manualNeeded.add(hit.placeName());
            return null;
        }
    }

    private ChatbotV1Dto.SkillResponse composeResponse(List<String> autoRegistered,
                                                      List<String> manualNeeded,
                                                      String memo) {
        StringBuilder sb = new StringBuilder();

        if (!autoRegistered.isEmpty()) {
            sb.append("✅ 장소 ").append(autoRegistered.size()).append("개가 저장되었어요\n");
            for (String n : autoRegistered) sb.append("• ").append(n).append('\n');
            if (memo != null && !memo.isBlank()) {
                sb.append("📝 메모: ").append(memo).append('\n');
            }
        }

        if (!manualNeeded.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("❓ 다음 장소는 정확하게 찾기 어려워 자동 저장하지 못했어요.\n");
            sb.append("앱에서 직접 등록해주세요:\n");
            for (String n : manualNeeded) sb.append("• ").append(n).append('\n');
        }

        if (sb.length() == 0) {
            return ChatbotV1Dto.SkillResponse.simple(
                    "장소를 찾지 못했어요. 앱에서 직접 등록해 주세요.");
        }

        return ChatbotV1Dto.SkillResponse.simple(sb.toString().trim());
    }

    private ChatbotV1Dto.SkillResponse handleLegacySingle(ParsedContent parsed,
                                                          String botUserKey,
                                                          Long userId,
                                                          Long groupId,
                                                          String instagramUrl,
                                                          String memo,
                                                          ChatbotContext ctx) {
        String keyword = parsed.placeKeyword();
        PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(keyword, ctx);
        if (outcome instanceof PlaceSearchOutcome.Single single) {
            try {
                Pin saved = pinService.registerFromInstagram(userId, groupId, single.hit(), instagramUrl, memo);
                twoSecondMemoSession.put(botUserKey, saved.getId());
                String msg = "장소가 저장되었어요: " + saved.getPlaceName();
                if (memo != null && !memo.isBlank()) msg += "\n📝 메모: " + memo;
                return ChatbotV1Dto.SkillResponse.simple(msg);
            } catch (DataIntegrityViolationException e) {
                return ChatbotV1Dto.SkillResponse.simple("이미 저장된 장소입니다.");
            }
        }
        if (outcome instanceof PlaceSearchOutcome.Multiple multiple) {
            return PlaceCardBuilder.buildMultipleCard(
                    botUserKey, multiple.hits(), instagramUrl, placeSelectionCandidateStore);
        }
        return handleGoogleFallback(botUserKey, userId, groupId, instagramUrl, keyword, memo, ctx);
    }

    private ChatbotV1Dto.SkillResponse handleGoogleFallback(String botUserKey,
                                                            Long userId,
                                                            Long groupId,
                                                            String instagramUrl,
                                                            String keyword,
                                                            String memo,
                                                            ChatbotContext ctx) {
        long threshold = placeProperties.search().googleSyncThresholdMs();
        if (ctx.remaining() >= threshold) {
            PlaceSearchOutcome syncOutcome = placeFallbackOrchestrator.runSync(keyword, ctx);
            if (syncOutcome instanceof PlaceSearchOutcome.Single single) {
                try {
                    Pin saved = pinService.registerFromInstagram(userId, groupId, single.hit(), instagramUrl, memo);
                    twoSecondMemoSession.put(botUserKey, saved.getId());
                    String msg = "장소가 저장되었어요: " + saved.getPlaceName();
                    if (memo != null && !memo.isBlank()) msg += "\n📝 메모: " + memo;
                    return ChatbotV1Dto.SkillResponse.simple(msg);
                } catch (DataIntegrityViolationException e) {
                    return ChatbotV1Dto.SkillResponse.simple("이미 저장된 장소입니다.");
                }
            }
            if (syncOutcome instanceof PlaceSearchOutcome.Multiple multiple) {
                return PlaceCardBuilder.buildMultipleCard(
                        botUserKey, multiple.hits(), instagramUrl, placeSelectionCandidateStore);
            }
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        }
        FallbackJobContext jobCtx = new FallbackJobContext(
                botUserKey, userId, groupId, /*callbackUrl*/"", instagramUrl, keyword);
        try {
            placeFallbackOrchestrator.runSync(keyword, ctx);
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        } finally {
            assert jobCtx != null;
        }
    }

    /** SkillResponse(simpleText)에서 본문 텍스트 추출. cards 등 다른 형태면 빈 문자열. */
    @SuppressWarnings("unchecked")
    private static String extractSimpleText(ChatbotV1Dto.SkillResponse resp) {
        if (resp == null || resp.template() == null || resp.template().outputs() == null
                || resp.template().outputs().isEmpty()) {
            return "";
        }
        Map<String, Object> first = resp.template().outputs().get(0);
        Object st = first.get("simpleText");
        if (st instanceof Map) {
            Object text = ((Map<String, Object>) st).get("text");
            if (text instanceof String s) return s;
        }
        return "";
    }
}
