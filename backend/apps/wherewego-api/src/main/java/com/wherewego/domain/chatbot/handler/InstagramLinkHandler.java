package com.wherewego.domain.chatbot.handler;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.ChatbotErrorMessages;
import com.wherewego.domain.chatbot.FallbackJobContext;
import com.wherewego.domain.chatbot.MessageType;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@Component
@RequiredArgsConstructor
public class InstagramLinkHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(InstagramLinkHandler.class);

    /** 사용자에게 노출할 confident=false 카드 최대 개수. 초과분은 이름만 안내. */
    private static final int MAX_CONFIRMATION_CARDS = 2;
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

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String url = request.userRequest().utterance().trim();

        Long userId = ctx.userId();
        if (userId == null) {
            Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
            if (userIdOpt.isEmpty()) {
                return ChatbotV1Dto.SkillResponse.simple("먼저 앱에서 발급한 6자리 연동코드를 보내주세요.");
            }
            userId = userIdOpt.get();
        }

        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("그룹에 먼저 참여해주세요.");
        }
        Long groupId = groupIdOpt.get();

        Optional<ContentParser> parserOpt = contentParserRegistry.resolve(url);
        if (parserOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("지원하지 않는 링크입니다.");
        }

        Optional<ParsedContent> parsedOpt;
        try {
            parsedOpt = parserOpt.get().parse(url, ctx);
        } catch (CoreException e) {
            log.warn("Instagram parse failed code={}", e.getErrorType().getCode());
            return ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e));
        }
        if (parsedOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾지 못했어요. 직접 검색해 주세요.");
        }

        List<PlaceCandidate> candidates = parsedOpt.get().candidates();
        if (candidates.isEmpty()) {
            // 신버전 candidates가 비어있으면 구버전 placeKeyword로 fallback (호환).
            return handleLegacySingle(parsedOpt.get(), botUserKey, userId, groupId, url, ctx, request);
        }

        // 비동기 callback 흐름: 즉시 대기 메시지 + 백그라운드에서 candidates 모두 처리 후 push.
        String callbackUrl = request.userRequest().callbackUrl();
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            return submitAsyncCandidates(botUserKey, userId, groupId, url, candidates, callbackUrl);
        }

        // callback 없는 환경 (테스트/시뮬레이터 일부): 동기 처리.
        return handleCandidates(botUserKey, userId, groupId, url, candidates, ctx);
    }

    /**
     * candidates 처리를 백그라운드 풀에 제출하고 즉시 useCallback 응답을 반환.
     * 백그라운드 작업이 끝나면 KakaoCallbackClient로 결과 push.
     */
    private ChatbotV1Dto.SkillResponse submitAsyncCandidates(String botUserKey,
                                                              Long userId,
                                                              Long groupId,
                                                              String instagramUrl,
                                                              List<PlaceCandidate> candidates,
                                                              String callbackUrl) {
        try {
            asyncCandidatesExecutor.execute(() -> {
                try {
                    ChatbotContext asyncCtx = ChatbotContext.start(ASYNC_CANDIDATES_DEADLINE_MS);
                    asyncCtx.setUserId(userId);
                    ChatbotV1Dto.SkillResponse result = handleCandidates(
                            botUserKey, userId, groupId, instagramUrl, candidates, asyncCtx);
                    kakaoCallbackClient.push(callbackUrl, result);
                } catch (RuntimeException e) {
                    log.error("Async candidates processing failed url={} cause={}",
                            instagramUrl, e.getMessage(), e);
                    kakaoCallbackClient.pushText(callbackUrl,
                            "장소 처리 중 오류가 발생했어요. 다시 시도해 주세요.");
                }
            });
            return ChatbotV1Dto.SkillResponse.useCallback(
                    candidates.size() + "개 장소를 찾고 있어요. 잠시만 기다려주세요...");
        } catch (RejectedExecutionException e) {
            log.warn("Async candidates queue full, falling through to sync url={}", instagramUrl);
            ChatbotContext syncCtx = ChatbotContext.start(3_500L);
            syncCtx.setUserId(userId);
            return handleCandidates(botUserKey, userId, groupId, instagramUrl, candidates, syncCtx);
        }
    }

    /**
     * 신버전 흐름 — confident=true만 Google Places 검색해서 자동 저장.
     * confident=false는 검색 API 호출조차 안 하고 "직접 등록" 안내 목록에 추가.
     * confident=true인데 검색 결과 없음 / deadline 초과한 잔여 candidates도 동일 안내 목록으로 통합.
     * 카드(BasicCard) 흐름 제거 — 모든 응답은 simpleText로 끝나며 사용자에게 재전송 요구하지 않는다.
     */
    private ChatbotV1Dto.SkillResponse handleCandidates(String botUserKey,
                                                        Long userId,
                                                        Long groupId,
                                                        String instagramUrl,
                                                        List<PlaceCandidate> candidates,
                                                        ChatbotContext ctx) {
        List<String> autoRegistered = new ArrayList<>();
        List<String> manualNeeded = new ArrayList<>();   // 모호 / 검색실패 / deadline 초과 통합

        Long lastSavedPinId = null;

        for (int i = 0; i < candidates.size(); i++) {
            PlaceCandidate cand = candidates.get(i);

            // deadline 초과 시 남은 후보 전부 manualNeeded로 누적 후 종료
            if (ctx.expired()) {
                for (int j = i; j < candidates.size(); j++) {
                    manualNeeded.add(candidates.get(j).name());
                }
                log.warn("Chatbot deadline hit, {} remaining candidates → manualNeeded",
                        candidates.size() - i);
                break;
            }

            // confident=false → Google Places 호출 절약, 즉시 manualNeeded
            if (!cand.confident()) {
                manualNeeded.add(cand.name());
                continue;
            }

            // confident=true → Google Places 검색
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(cand.name(), ctx);

            if (outcome instanceof PlaceSearchOutcome.Empty) {
                manualNeeded.add(cand.name());
                continue;
            }

            if (outcome instanceof PlaceSearchOutcome.Single single) {
                Long savedId = tryRegister(userId, groupId, single.hit(), instagramUrl,
                        autoRegistered, manualNeeded);
                if (savedId != null) lastSavedPinId = savedId;
                continue;
            }

            // Multiple — confident=true이므로 첫 번째 결과 자동 등록
            List<PlaceSearchHit> hits = ((PlaceSearchOutcome.Multiple) outcome).hits();
            Long savedId = tryRegister(userId, groupId, hits.get(0), instagramUrl,
                    autoRegistered, manualNeeded);
            if (savedId != null) lastSavedPinId = savedId;
        }

        // 2초 메모 세션은 가장 마지막으로 등록한 핀 하나에 연결.
        if (lastSavedPinId != null) {
            twoSecondMemoSession.put(botUserKey, lastSavedPinId);
        }

        return composeResponse(autoRegistered, manualNeeded);
    }

    /** 핀 등록 시도, 결과를 lists에 분류 누적. 저장된 pinId 반환(중복/실패 시 null). */
    private Long tryRegister(Long userId,
                             Long groupId,
                             PlaceSearchHit hit,
                             String instagramUrl,
                             List<String> autoRegistered,
                             List<String> autoFailed) {
        try {
            Pin saved = pinService.registerFromInstagram(userId, groupId, hit, instagramUrl);
            autoRegistered.add(saved.getPlaceName());
            return saved.getId();
        } catch (DataIntegrityViolationException e) {
            // 같은 (group, url, place_name) 이미 등록됨 — silent skip
            return null;
        } catch (RuntimeException e) {
            log.warn("registerFromInstagram failed name={} cause={}", hit.placeName(), e.getMessage());
            autoFailed.add(hit.placeName());
            return null;
        }
    }

    /**
     * 자동 등록 + 직접 등록 필요한 곳을 한 simpleText로 구성.
     * 카드 흐름 제거. 재전송 요구 문구 없음.
     */
    private ChatbotV1Dto.SkillResponse composeResponse(List<String> autoRegistered,
                                                       List<String> manualNeeded) {
        StringBuilder sb = new StringBuilder();

        if (!autoRegistered.isEmpty()) {
            sb.append("✅ 장소 ").append(autoRegistered.size()).append("개가 저장되었어요\n");
            for (String n : autoRegistered) sb.append("• ").append(n).append('\n');
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
     * 구버전(또는 candidates 빈 경우) fallback — 단일 placeKeyword 흐름.
     * 기존 동기/비동기 fallback orchestrator 흐름 보존.
     */
    private ChatbotV1Dto.SkillResponse handleLegacySingle(ParsedContent parsed,
                                                          String botUserKey,
                                                          Long userId,
                                                          Long groupId,
                                                          String instagramUrl,
                                                          ChatbotContext ctx,
                                                          ChatbotV1Dto.SkillRequest request) {
        String keyword = parsed.placeKeyword();
        PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(keyword, ctx);
        if (outcome instanceof PlaceSearchOutcome.Single single) {
            try {
                Pin saved = pinService.registerFromInstagram(userId, groupId, single.hit(), instagramUrl);
                twoSecondMemoSession.put(botUserKey, saved.getId());
                return ChatbotV1Dto.SkillResponse.simple("장소가 저장되었어요: " + saved.getPlaceName());
            } catch (DataIntegrityViolationException e) {
                return ChatbotV1Dto.SkillResponse.simple("이미 저장된 장소입니다.");
            }
        }
        if (outcome instanceof PlaceSearchOutcome.Multiple multiple) {
            return PlaceCardBuilder.buildMultipleCard(
                    botUserKey, multiple.hits(), instagramUrl, placeSelectionCandidateStore);
        }
        return handleGoogleFallback(botUserKey, userId, groupId, instagramUrl, keyword, ctx, request);
    }

    private ChatbotV1Dto.SkillResponse handleGoogleFallback(String botUserKey,
                                                            Long userId,
                                                            Long groupId,
                                                            String instagramUrl,
                                                            String keyword,
                                                            ChatbotContext ctx,
                                                            ChatbotV1Dto.SkillRequest request) {
        long threshold = placeProperties.search().googleSyncThresholdMs();
        if (ctx.remaining() >= threshold) {
            PlaceSearchOutcome syncOutcome = placeFallbackOrchestrator.runSync(keyword, ctx);
            if (syncOutcome instanceof PlaceSearchOutcome.Single single) {
                try {
                    Pin saved = pinService.registerFromInstagram(userId, groupId, single.hit(), instagramUrl);
                    twoSecondMemoSession.put(botUserKey, saved.getId());
                    return ChatbotV1Dto.SkillResponse.simple("장소가 저장되었어요: " + saved.getPlaceName());
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

        String callbackUrl = request.userRequest().callbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        }

        FallbackJobContext jobCtx = new FallbackJobContext(
                botUserKey, userId, groupId, callbackUrl, instagramUrl, keyword
        );
        try {
            placeFallbackOrchestrator.runAsync(keyword, jobCtx);
            return ChatbotV1Dto.SkillResponse.useCallback("장소를 찾고 있어요. 잠시만 기다려주세요.");
        } catch (RejectedExecutionException e) {
            log.warn("place fallback rejected keyword={}", keyword);
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        }
    }
}
