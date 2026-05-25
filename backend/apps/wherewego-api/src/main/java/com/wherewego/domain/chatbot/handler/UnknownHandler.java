package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.PendingInstagramSession;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UnknownHandler implements MessageHandler {

    private final BotUserMappingService botUserMappingService;
    private final PendingInstagramSession pendingInstagramSession;

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

        // (2) 연동·pending 있음 — 메모 입력 대기 안내 + "메모 없이 저장" QuickReply.
        if (pendingInstagramSession.peek(botUserKey).isPresent()) {
            return ChatbotV1Dto.SkillResponse.simple(
                    "메모 입력을 기다리고 있어요.\n"
                            + "메모를 보내거나 아래 [❌ 메모 없이 저장]을 눌러주세요.",
                    List.of(ChatbotV1Dto.QuickReply.message("❌ 메모 없이 저장", "메모 없이 저장"))
            );
        }

        // (3) 연동·pending 없음 — QuickReply 없이 일반 안내.
        // RecentlyAutoSavedSession은 URL 키 기반이라 사용자별 직접 peek 불가, 본 분기로 통합.
        return ChatbotV1Dto.SkillResponse.simple(
                "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요.\n"
                        + "혹시 방금 링크를 보내셨다면 처리 중일 수 있어요. 결과가 도착하지 않으면 잠시 후 아무 메시지나 보내주세요."
        );
    }
}
