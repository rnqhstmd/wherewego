package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.PendingInstagramSession;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 인스타 URL 직후의 메모 입력 메시지 처리.
 *
 * <p>분기:
 * <ul>
 *   <li>"취소" → pending 해제 + 안내</li>
 *   <li>"저장" → 메모 없이 candidates 처리</li>
 *   <li>그 외 텍스트 → 그 텍스트를 메모로 사용해서 candidates 처리</li>
 * </ul>
 *
 * <p>실제 candidates 처리는 {@link InstagramLinkHandler#processWithMemoAsync}에 위임.
 * 본 핸들러는 분기 + pending 해제만 담당.</p>
 */
@Component
@RequiredArgsConstructor
public class InstagramPendingMemoHandler implements MessageHandler {

    private final PendingInstagramSession pendingInstagramSession;
    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final InstagramLinkHandler instagramLinkHandler;

    @Override
    public MessageType supports() {
        return MessageType.INSTAGRAM_PENDING_MEMO;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        Optional<String> pendingOpt = pendingInstagramSession.peek(botUserKey);
        if (pendingOpt.isEmpty()) {
            // 동시성으로 pending이 사라진 경우 — 일반 안내로 fallback
            return ChatbotV1Dto.SkillResponse.simple(
                    "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요.");
        }
        String instagramUrl = pendingOpt.get();

        String utterance = request.userRequest().utterance() == null
                ? ""
                : request.userRequest().utterance().trim();

        // 취소
        if ("취소".equals(utterance)) {
            pendingInstagramSession.invalidate(botUserKey);
            return ChatbotV1Dto.SkillResponse.simple("취소되었어요. 새 링크를 보내주시면 다시 등록할 수 있어요.");
        }

        // 메모 없이 저장
        String memo;
        if ("저장".equals(utterance)) {
            memo = null;
        } else {
            memo = utterance;
        }

        // userId / groupId 가드 (방어적)
        Long userId = ctx.userId();
        if (userId == null) {
            Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
            if (userIdOpt.isEmpty()) {
                pendingInstagramSession.invalidate(botUserKey);
                return ChatbotV1Dto.SkillResponse.simple(
                        "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러주세요.");
            }
            userId = userIdOpt.get();
        }
        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            pendingInstagramSession.invalidate(botUserKey);
            return ChatbotV1Dto.SkillResponse.simple("그룹에 먼저 참여해주세요.");
        }

        // pending은 처리 시작 시점에 invalidate (실패해도 같은 URL 재시도는 사용자가 새로 보내야 함)
        pendingInstagramSession.invalidate(botUserKey);

        String callbackUrl = request.userRequest().callbackUrl();
        return instagramLinkHandler.processWithMemoAsync(
                botUserKey, userId, groupIdOpt.get(), instagramUrl, memo, callbackUrl);
    }
}
