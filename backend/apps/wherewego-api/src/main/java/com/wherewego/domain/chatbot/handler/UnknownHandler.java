package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import org.springframework.stereotype.Component;

@Component
public class UnknownHandler implements MessageHandler {

    @Override
    public MessageType supports() {
        return MessageType.UNKNOWN;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        return ChatbotV1Dto.SkillResponse.simple(
                "장소 등록은 인스타그램 링크를 보내주세요. 연동은 앱에서 발급한 6자리 숫자를 입력하세요."
        );
    }
}
