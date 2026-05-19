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
     * 신버전 흐름 — confident=true는 자동 등록, false는 처음 N개만 카드, 초과분은 이름 안내.
     */
    private ChatbotV1Dto.SkillResponse handleCandidates(String botUserKey,
                                                        Long userId,
                                                        Long groupId,
                                                        String instagramUrl,
                                                        List<PlaceCandidate> candidates,
                                                        ChatbotContext ctx) {
        List<String> autoRegistered = new ArrayList<>();
        List<String> autoFailed = new ArrayList<>();          // 등록 시도 후 실패/skip된 이름
        List<Map<String, Object>> cardOutputs = new ArrayList<>();
        List<String> skippedMoreCards = new ArrayList<>();    // 카드 max 초과로 버려진 이름
        List<String> timeoutSkipped = new ArrayList<>();      // 카카오 데드라인으로 처리 못한 이름

        Long lastSavedPinId = null;

        for (int i = 0; i < candidates.size(); i++) {
            PlaceCandidate cand = candidates.get(i);
            if (ctx.expired()) {
                for (int j = i; j < candidates.size(); j++) {
                    timeoutSkipped.add(candidates.get(j).name());
                }
                log.warn("Chatbot deadline hit, {} remaining candidates skipped", candidates.size() - i);
                break;
            }
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(cand.name(), ctx);

            // 결과 없음 → skip + 안내문에만 노출
            if (outcome instanceof PlaceSearchOutcome.Empty) {
                autoFailed.add(cand.name());
                continue;
            }

            // Single → 즉시 자동 등록
            if (outcome instanceof PlaceSearchOutcome.Single single) {
                Long savedId = tryRegister(userId, groupId, single.hit(), instagramUrl, autoRegistered, autoFailed);
                if (savedId != null) lastSavedPinId = savedId;
                continue;
            }

            // Multiple
            List<PlaceSearchHit> hits = ((PlaceSearchOutcome.Multiple) outcome).hits();
            if (cand.confident()) {
                // confident=true → 첫 번째 자동 등록
                Long savedId = tryRegister(userId, groupId, hits.get(0), instagramUrl, autoRegistered, autoFailed);
                if (savedId != null) lastSavedPinId = savedId;
            } else if (cardOutputs.size() < MAX_CONFIRMATION_CARDS) {
                // 모호 + 카드 슬롯 여유 → 카드로
                cardOutputs.add(PlaceCardBuilder.buildCardOutput(
                        botUserKey, hits, instagramUrl, placeSelectionCandidateStore, cand.name()));
            } else {
                // 모호 + 카드 슬롯 초과 → 이름만 안내
                skippedMoreCards.add(cand.name());
            }
        }

        // 2초 메모 세션은 가장 마지막으로 등록한 핀 하나에 연결.
        if (lastSavedPinId != null) {
            twoSecondMemoSession.put(botUserKey, lastSavedPinId);
        }

        return composeResponse(autoRegistered, autoFailed, cardOutputs, skippedMoreCards, timeoutSkipped);
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

    /** 자동등록/카드/스킵 결과들을 한 SkillResponse(outputs[])로 구성. */
    private ChatbotV1Dto.SkillResponse composeResponse(List<String> autoRegistered,
                                                       List<String> autoFailed,
                                                       List<Map<String, Object>> cardOutputs,
                                                       List<String> skippedMoreCards,
                                                       List<String> timeoutSkipped) {
        List<Map<String, Object>> outputs = new ArrayList<>();

        // 1) 자동 등록 결과 simpleText (있을 때만)
        StringBuilder topText = new StringBuilder();
        if (!autoRegistered.isEmpty()) {
            topText.append("장소 ").append(autoRegistered.size()).append("개가 저장되었어요\n");
            for (String n : autoRegistered) topText.append("• ").append(n).append('\n');
        }

        // 2) 카드 출력 (max N개)
        outputs.addAll(cardOutputs);

        // 3) 안내문 (스킵된 후보들) — 카드 슬롯 초과 + 검색 실패 모두 사용자에게 알림
        StringBuilder skipText = new StringBuilder();
        if (!skippedMoreCards.isEmpty()) {
            skipText.append("⚠ 추가로 추출됐지만 사용자 선택이 필요해 이번에는 등록되지 않은 곳: ")
                    .append(String.join(", ", skippedMoreCards))
                    .append("\n");
        }
        if (!autoFailed.isEmpty()) {
            skipText.append("⚠ 검색 결과를 찾지 못한 곳: ")
                    .append(String.join(", ", autoFailed))
                    .append("\n");
        }
        if (!timeoutSkipped.isEmpty()) {
            skipText.append("⏱ 시간이 부족해 이번에는 처리하지 못한 곳: ")
                    .append(String.join(", ", timeoutSkipped))
                    .append("\n해당 링크를 다시 한 번 보내주시면 이어서 등록됩니다.");
        }

        if (topText.length() == 0 && outputs.isEmpty() && skipText.length() == 0) {
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾지 못했어요. 직접 검색해 주세요.");
        }

        // 자동 등록 simpleText는 카드보다 위로 (있으면)
        if (topText.length() > 0) {
            outputs.add(0, PlaceCardBuilder.simpleTextOutput(topText.toString().trim()));
        }
        if (skipText.length() > 0) {
            outputs.add(PlaceCardBuilder.simpleTextOutput(skipText.toString().trim()));
        }
        return ChatbotV1Dto.SkillResponse.cards(outputs);
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
