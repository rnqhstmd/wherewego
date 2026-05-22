package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UnknownHandler implements MessageHandler {

    @Override
    public MessageType supports() {
        return MessageType.UNKNOWN;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        return ChatbotV1Dto.SkillResponse.simple(
                "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요.\n\n"
                        + "혹시 방금 링크나 메모를 보내셨나요?\n"
                        + "처리에 시간이 걸릴 경우 결과가 이 메시지 위에 함께 표시돼요.\n"
                        + "그룹 연동이 필요하면 아래 [🔗 그룹 연동하기]를 눌러주세요.",
                List.of(ChatbotV1Dto.QuickReply.message("🔗 그룹 연동하기", "그룹 연동하기"))
        );
    }
}
