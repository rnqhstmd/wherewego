package com.wherewego.interfaces.api.chatbot;

import com.wherewego.domain.chatbot.ChatbotWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chatbot")
@RequiredArgsConstructor
public class ChatbotV1Controller implements ChatbotV1ApiSpec {

    private final ChatbotWebhookService webhookService;

    @PostMapping("/webhook")
    @Override
    public ChatbotV1Dto.SkillResponse webhook(@RequestBody ChatbotV1Dto.SkillRequest request) {
        return webhookService.handle(request);
    }
}
