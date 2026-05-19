package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.ChatbotErrorMessages;
import com.wherewego.domain.chatbot.MessageClassifier;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import com.wherewego.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * "그룹 연동" 블록 처리.
 *
 * <p>카카오 i 오픈빌더 시나리오에서 slot filling으로 받은 사용자 입력을
 * {@code action.params.code}로 전달받는다. utterance 자체는 사용하지 않는다 —
 * 일반 6자리 숫자 메시지와 충돌을 방지하기 위한 의도적 분리.</p>
 */
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
        String code = MessageClassifier.extractParam(request, "code");
        if (code == null || code.isBlank()) {
            return ChatbotV1Dto.SkillResponse.simple(
                    "연동 코드를 받지 못했어요. 챗봇 메뉴에서 [그룹 연동하기]를 다시 눌러주세요."
            );
        }
        code = code.trim();

        if (botUserMappingService.resolveUserId(botUserKey).isPresent()) {
            return ChatbotV1Dto.SkillResponse.simple("이미 연동된 계정입니다.");
        }

        try {
            botUserMappingService.link(code, botUserKey, Instant.now());
            return ChatbotV1Dto.SkillResponse.simple("연동이 완료되었어요. 인스타그램 릴스 링크를 보내면 자동으로 장소가 저장됩니다.");
        } catch (CoreException e) {
            return ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e));
        }
    }
}
