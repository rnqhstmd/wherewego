package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.pin.PinMemoService;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TwoSecondMemoHandler implements MessageHandler {

    private final TwoSecondMemoSession twoSecondMemoSession;
    private final BotUserMappingService botUserMappingService;
    private final PinMemoService pinMemoService;

    @Override
    public MessageType supports() {
        return MessageType.TEXT_2SEC_CANDIDATE;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();

        Optional<Long> pinIdOpt = twoSecondMemoSession.peek(botUserKey);
        if (pinIdOpt.isEmpty()) {
            // classifier 이후 윈도우가 만료된 race — 빈 응답 (사용자에게 추가 안내 없음)
            return ChatbotV1Dto.SkillResponse.empty();
        }

        Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
        if (userIdOpt.isEmpty()) {
            twoSecondMemoSession.invalidate(botUserKey);
            return ChatbotV1Dto.SkillResponse.empty();
        }

        String memo = request.userRequest().utterance() == null
                ? ""
                : request.userRequest().utterance().strip();

        pinMemoService.attachAutoMemoIfWithinWindow(pinIdOpt.get(), userIdOpt.get(), memo);
        twoSecondMemoSession.invalidate(botUserKey);
        return ChatbotV1Dto.SkillResponse.empty();
    }
}
