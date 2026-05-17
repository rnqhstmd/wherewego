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
import com.wherewego.domain.place.PlaceFallbackOrchestrator;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.PlaceSearchOutcome;
import com.wherewego.domain.place.PlaceSearchService;
import com.wherewego.domain.place.PlaceSelectionCandidateStore;
import com.wherewego.domain.place.parser.ContentParser;
import com.wherewego.domain.place.parser.ContentParserRegistry;
import com.wherewego.domain.place.parser.ParsedContent;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import com.wherewego.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

@Component
@RequiredArgsConstructor
public class InstagramLinkHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(InstagramLinkHandler.class);

    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final ContentParserRegistry contentParserRegistry;
    private final PlaceSearchService placeSearchService;
    private final PinService pinService;
    private final PlaceSelectionCandidateStore placeSelectionCandidateStore;
    private final TwoSecondMemoSession twoSecondMemoSession;
    private final PlaceFallbackOrchestrator placeFallbackOrchestrator;
    private final PlaceProperties placeProperties;

    @Override
    public MessageType supports() {
        return MessageType.INSTAGRAM_LINK;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String url = request.userRequest().utterance().trim();

        // userId 는 WebhookService 미연동 가드에서 이미 1회 조회 후 ctx 에 캐싱.
        // 비정상 경로 방어 차원에서 null 인 경우 한 번 더 조회한다.
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
            log.debug("Instagram parse failed detail url={} code={}", url, e.getErrorType().getCode());
            return ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e));
        }
        if (parsedOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾지 못했어요. 직접 검색해 주세요.");
        }

        String keyword = parsedOpt.get().placeKeyword();
        PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(keyword, ctx);
        if (outcome instanceof PlaceSearchOutcome.Single single) {
            return handleSingle(botUserKey, userId, groupId, single.hit(), url);
        }
        if (outcome instanceof PlaceSearchOutcome.Multiple multiple) {
            return handleMultiple(botUserKey, multiple.hits(), url);
        }
        // outcome == Empty → Google 폴백 분기
        return handleGoogleFallback(botUserKey, userId, groupId, url, keyword, ctx, request);
    }

    private ChatbotV1Dto.SkillResponse handleSingle(String botUserKey,
                                                    Long userId,
                                                    Long groupId,
                                                    PlaceSearchHit hit,
                                                    String instagramUrl) {
        try {
            Pin saved = pinService.registerFromInstagram(userId, groupId, hit, instagramUrl);
            twoSecondMemoSession.put(botUserKey, saved.getId());
            return ChatbotV1Dto.SkillResponse.simple("장소가 저장되었어요: " + saved.getPlaceName());
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate pin groupId={} url={}", groupId, instagramUrl);
            return ChatbotV1Dto.SkillResponse.simple("이미 저장된 장소입니다.");
        }
    }

    private ChatbotV1Dto.SkillResponse handleMultiple(String botUserKey,
                                                      List<PlaceSearchHit> hits,
                                                      String instagramUrl) {
        return PlaceCardBuilder.buildMultipleCard(botUserKey, hits, instagramUrl, placeSelectionCandidateStore);
    }

    private ChatbotV1Dto.SkillResponse handleGoogleFallback(String botUserKey,
                                                            Long userId,
                                                            Long groupId,
                                                            String instagramUrl,
                                                            String keyword,
                                                            ChatbotContext ctx,
                                                            ChatbotV1Dto.SkillRequest request) {
        long threshold = placeProperties.search().googleSyncThresholdMs();

        // 동기 경로: 잔여 시간 충분
        if (ctx.remaining() >= threshold) {
            PlaceSearchOutcome syncOutcome = placeFallbackOrchestrator.runSync(keyword, ctx);
            if (syncOutcome instanceof PlaceSearchOutcome.Single single) {
                return handleSingle(botUserKey, userId, groupId, single.hit(), instagramUrl);
            }
            if (syncOutcome instanceof PlaceSearchOutcome.Multiple multiple) {
                return handleMultiple(botUserKey, multiple.hits(), instagramUrl);
            }
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        }

        // 비동기 경로: callbackUrl 존재 시
        String callbackUrl = request.userRequest().callbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        }

        FallbackJobContext jobCtx = new FallbackJobContext(
                botUserKey, userId, groupId, callbackUrl, instagramUrl, keyword, System.currentTimeMillis()
        );
        try {
            placeFallbackOrchestrator.runAsync(keyword, jobCtx);
            return ChatbotV1Dto.SkillResponse.useCallback("장소를 찾고 있어요. 잠시만 기다려주세요.");
        } catch (RejectedExecutionException e) {
            // 큐 가득참 — Tomcat 워커 블로킹 회피를 위해 콜백 푸시는 생략 (TIMEOUT 3s × queue full 상황).
            log.warn("place fallback rejected keyword={}", keyword);
            return ChatbotV1Dto.SkillResponse.simple("장소를 찾을 수 없습니다.");
        }
    }
}
