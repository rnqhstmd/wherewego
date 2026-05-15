package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.domain.place.PlaceSelectionCandidateStore;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PlaceSelectionHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(PlaceSelectionHandler.class);

    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final PinService pinService;
    private final PlaceSelectionCandidateStore placeSelectionCandidateStore;
    private final TwoSecondMemoSession twoSecondMemoSession;

    @Override
    public MessageType supports() {
        return MessageType.PLACE_SELECTION;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String placeId = extractPlaceId(request);

        Optional<PlaceSelectionCandidateStore.Entry> entryOpt =
                placeSelectionCandidateStore.takeAndInvalidate(botUserKey, placeId);
        if (entryOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("선택 시간이 만료되었어요. 링크를 다시 보내 주세요.");
        }
        PlaceSelectionCandidateStore.Entry entry = entryOpt.get();

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

        try {
            Pin saved = pinService.registerFromSelection(userId, groupId, entry.hit(), entry.instagramUrl());
            twoSecondMemoSession.put(botUserKey, saved.getId());
            return ChatbotV1Dto.SkillResponse.simple("장소가 저장되었어요: " + saved.getPlaceName());
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate pin groupId={} placeId={}", groupId, placeId);
            return ChatbotV1Dto.SkillResponse.simple("이미 저장된 장소입니다.");
        }
    }

    /**
     * 카카오 i 오픈빌더 버튼 {@code action="message"} 전송 시 {@code extra}는
     * 요청의 {@code action.clientExtra}로 들어온다. clientExtra 우선, params 폴백.
     */
    private static String extractPlaceId(ChatbotV1Dto.SkillRequest request) {
        if (request == null || request.action() == null) {
            return null;
        }
        ChatbotV1Dto.Action action = request.action();
        String placeId = null;
        if (action.clientExtra() != null) {
            placeId = action.clientExtra().get("placeId");
        }
        if ((placeId == null || placeId.isBlank()) && action.params() != null) {
            placeId = action.params().get("placeId");
        }
        return placeId;
    }
}
