package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.ChatbotErrorMessages;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Override
    public MessageType supports() {
        return MessageType.INSTAGRAM_LINK;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String url = request.userRequest().utterance().trim();

        Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
        if (userIdOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("먼저 앱에서 발급한 6자리 연동코드를 보내주세요.");
        }
        Long userId = userIdOpt.get();

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

        PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(parsedOpt.get().placeKeyword(), ctx);
        if (outcome instanceof PlaceSearchOutcome.Single single) {
            return handleSingle(botUserKey, userId, groupId, single.hit(), url);
        }
        if (outcome instanceof PlaceSearchOutcome.Multiple multiple) {
            return handleMultiple(botUserKey, multiple.hits(), url);
        }
        return ChatbotV1Dto.SkillResponse.simple("장소를 찾지 못했어요. 직접 검색해 주세요.");
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
        List<ChatbotV1Dto.Button> buttons = new ArrayList<>();
        for (PlaceSearchHit hit : hits) {
            placeSelectionCandidateStore.put(
                    botUserKey,
                    hit.kakaoPlaceId(),
                    new PlaceSelectionCandidateStore.Entry(hit, instagramUrl)
            );
            buttons.add(new ChatbotV1Dto.Button(
                    hit.placeName(),
                    "message",
                    hit.placeName(),
                    Map.of("placeId", hit.kakaoPlaceId())
            ));
        }

        StringBuilder description = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            PlaceSearchHit hit = hits.get(i);
            description.append(i + 1).append(". ").append(hit.placeName());
            if (hit.address() != null && !hit.address().isBlank()) {
                description.append(" — ").append(hit.address());
            }
            description.append('\n');
        }

        ChatbotV1Dto.BasicCard card = new ChatbotV1Dto.BasicCard(
                "장소를 선택해 주세요",
                description.toString().trim(),
                buttons
        );
        List<Map<String, Object>> outputs = new ArrayList<>();
        outputs.add(Map.of("basicCard", card));
        return ChatbotV1Dto.SkillResponse.cards(outputs);
    }
}
