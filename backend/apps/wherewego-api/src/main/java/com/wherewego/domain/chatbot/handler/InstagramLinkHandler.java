package com.wherewego.domain.chatbot.handler;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.ChatbotErrorMessages;
import com.wherewego.domain.chatbot.FallbackJobContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.PendingInstagramSession;
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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 인스타 링크 수신 핸들러 — 신정책 (메모 입력 분리 흐름).
 *
 * <p>흐름:
 * <ol>
 *   <li>사용자가 인스타 URL 보냄 → {@link #handle} 이 PendingInstagramSession에 URL 저장 + "메모 보내주세요" 안내.
 *       실제 candidates 처리는 시작하지 않음.</li>
 *   <li>사용자가 다음 메시지(메모/저장/취소/새 URL) 보냄 → MessageClassifier가 INSTAGRAM_PENDING_MEMO로 분류 →
 *       {@code InstagramPendingMemoHandler}가 처리 분기. 메모/저장 선택 시 본 핸들러의
 *       {@link #processWithMemoAsync}를 호출.</li>
 *   <li>{@link #processWithMemoAsync}는 useCallback 응답 + 백그라운드 candidates 처리 + 카카오 callback push.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class InstagramLinkHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(InstagramLinkHandler.class);

    /** 비동기 candidates 처리에 적용하는 deadline (카카오 callback ttl ~1분). */
    private static final long ASYNC_CANDIDATES_DEADLINE_MS = 50_000L;

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

    /** N개 candidates 비동기 처리용 풀. 4 thread 정도면 본인+여친 use case 충분. */
    private final ExecutorService asyncCandidatesExecutor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "instagram-candidates-async");
                t.setDaemon(true);
                return t;
            });

    @Override
    public MessageType supports() {
        return MessageType.INSTAGRAM_LINK;
    }

    /**
     * 인스타 URL 수신 시: 가드 → pending 세션 저장 → 메모 입력 안내 응답.
     * 실제 candidates 처리는 사용자가 다음 메시지(메모/저장/취소)를 보낸 시점에 시작.
     */
    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String url = request.userRequest().utterance().trim();

        // userId 가드는 WebhookService에서 이미 했지만 본 핸들러에서도 방어적으로 한 번 더.
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

        // 새 인스타 URL은 이전 pending을 덮어쓴다 (사용자가 메모 안 보내고 다른 링크 보낸 경우).
        pendingInstagramSession.put(botUserKey, url);

        return ChatbotV1Dto.SkillResponse.simple(
                "📝 이 링크와 함께 저장할 메모를 보내주세요.\n"
                        + "메모 없이 저장하거나 취소하려면 아래 버튼을 눌러주세요.",
                memoQuickReplies());
    }

    /** 메모 안내 응답 하단에 노출되는 빠른답장(저장/취소). */
    private static java.util.List<ChatbotV1Dto.QuickReply> memoQuickReplies() {
        return java.util.List.of(
                ChatbotV1Dto.QuickReply.message("💾 메모 없이 저장", "저장"),
                ChatbotV1Dto.QuickReply.message("❌ 취소", "취소")
        );
    }

    /**
     * 메모 받은 후 candidates 처리. PendingMemoHandler가 호출.
     * 즉시 useCallback 응답을 반환하고, 백그라운드 풀에서 candidates 처리 + 카카오 callback push.
     * callbackUrl이 없으면 동기 처리 fallback.
     *
     * @param memo null/blank 이면 메모 없이 저장
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
        return runParseAndCandidates(
                parserOpt.get(), botUserKey, userId, groupId, instagramUrl, memo, ctxOrDefault(ctxNull(), userId));
    }

    private static ChatbotContext ctxNull() {
        return ChatbotContext.start(ASYNC_CANDIDATES_DEADLINE_MS);
    }

    private static ChatbotContext ctxOrDefault(ChatbotContext c, Long userId) {
        c.setUserId(userId);
        return c;
    }

    /**
     * 인스타 parse → candidates 처리 → 응답 조립. 비동기/동기 양쪽에서 호출.
     */
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
            // 구버전 placeKeyword fallback
            return handleLegacySingle(parsedOpt.get(), botUserKey, userId, groupId, instagramUrl, memo, ctx);
        }
        return handleCandidates(botUserKey, userId, groupId, instagramUrl, candidates, memo, ctx);
    }

    /**
     * confident=true만 Google Places 검색해서 자동 저장 (메모 포함).
     * confident=false / 검색실패 / deadline 초과는 모두 "직접 등록" 안내.
     */
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

    /** 핀 등록 시도 (memo 포함). 결과를 lists에 누적. */
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

    /** 응답 조립 — simpleText 1개. 메모가 있으면 안내문에 포함. */
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

    /**
     * 구버전(candidates 빈 경우) fallback — 단일 placeKeyword 흐름.
     * 메모는 등록 성공 시 함께 저장.
     */
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
        // 메모 흐름에선 callbackUrl 비동기 fallback orchestrator 사용 안 함 (이미 비동기).
        FallbackJobContext jobCtx = new FallbackJobContext(
                botUserKey, userId, groupId, /*callbackUrl*/"", instagramUrl, keyword);
        // 동기 best-effort
        try {
            placeFallbackOrchestrator.runSync(keyword, ctx);
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        } finally {
            // unused but keeps reference signature
            assert jobCtx != null;
        }
    }
}
