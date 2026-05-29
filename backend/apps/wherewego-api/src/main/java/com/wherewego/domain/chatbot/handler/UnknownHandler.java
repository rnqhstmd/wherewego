package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UnknownHandler implements MessageHandler {

    private final BotUserMappingService botUserMappingService;

    @Override
    public MessageType supports() {
        return MessageType.UNKNOWN;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();

        // (1) 미연동 — 그룹 연동 안내 + QuickReply.
        if (botUserMappingService.resolveUserId(botUserKey).isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple(
                    "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요.\n"
                            + "그룹 연동이 필요하면 아래 [🔗 그룹 연동하기]를 눌러주세요.",
                    List.of(ChatbotV1Dto.QuickReply.message("🔗 그룹 연동하기", "그룹 연동하기"))
            );
        }

        // (2) 연동·세션 없음 — QuickReply 없이 일반 안내.
        // Phase 12 ReelSavedSelectionSession 활성 상태는 classifier 에서 별도 enum 으로 분기되므로
        // 여기까지 도달한 발화는 세션 외(텍스트 잡음 등)로 본다.
        return ChatbotV1Dto.SkillResponse.simple(
                "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요.\n"
                        + "혹시 방금 링크를 보내셨다면 처리 중일 수 있어요. 결과가 도착하지 않으면 잠시 후 아무 메시지나 보내주세요."
        );
    }
}
