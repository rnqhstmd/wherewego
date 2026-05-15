package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.ChatbotErrorMessages;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import com.wherewego.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class LinkCodeHandler implements MessageHandler {

    private final BotUserMappingService botUserMappingService;

    @Override
    public MessageType supports() {
        return MessageType.LINK_CODE;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String code = request.userRequest().utterance().trim();

        if (botUserMappingService.resolveUserId(botUserKey).isPresent()) {
            return ChatbotV1Dto.SkillResponse.simple("이미 연동된 계정입니다.");
        }

        try {
            botUserMappingService.link(code, botUserKey, Instant.now());
            return ChatbotV1Dto.SkillResponse.simple("연동이 완료되었어요. 인스타그램 링크를 보내 장소를 저장해 보세요.");
        } catch (CoreException e) {
            return ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e));
        }
    }
}
