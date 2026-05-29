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
            // 폴백: 카카오 slot 파라미터(code) 누락 시 사용자가 채팅으로 직접 입력한 6자리 코드를 구제.
            // MessageClassifier 가 동일 조건(미연동 + 6자리)으로 LINK_CODE 라우팅한 케이스와 짝을 이룬다.
            String utterance = request.userRequest().utterance();
            if (utterance != null && utterance.trim().matches("\\d{6}")) {
                code = utterance.trim();
            }
        }
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
            return ChatbotV1Dto.SkillResponse.simple(
                    "연동이 완료되었어요! 🎉\n"
                            + "인스타 릴스 링크를 보내면 장소가 자동으로 저장돼요.\n\n"
                            + "핀은 이렇게 자라요:\n"
                            + "📍 발견 → ✨ 위시 → 💖 추억\n"
                            + "· 발견: 일단 모아둔 곳\n"
                            + "· 위시: 가보고 싶다고 고른 곳\n"
                            + "· 추억: 다녀온 곳");
        } catch (CoreException e) {
            return ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e));
        }
    }
}
