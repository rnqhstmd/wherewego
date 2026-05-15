package com.wherewego.domain.chatbot;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.handler.MessageHandler;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import com.wherewego.support.error.CoreException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Skill webhook 진입 서비스. {@code t0} 캡처 → 분류 → 핸들러 위임 → 폴백 try-catch 안전망.
 *
 * <p>{@link ChatbotV1Dto.SkillResponse} 자체 반환 — {@code ApiResponse} 래핑 미사용,
 * {@code ApiControllerAdvice} 영향을 받지 않도록 모든 예외를 본 서비스에서 SimpleText 변환한다.</p>
 */
@Service
public class ChatbotWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotWebhookService.class);

    private final MessageClassifier classifier;
    private final BotUserMappingService botUserMappingService;
    private final PlaceProperties placeProperties;
    private final List<MessageHandler> handlers;

    private final Map<MessageType, MessageHandler> routerMap = new EnumMap<>(MessageType.class);

    public ChatbotWebhookService(MessageClassifier classifier,
                                 BotUserMappingService botUserMappingService,
                                 PlaceProperties placeProperties,
                                 List<MessageHandler> handlers) {
        this.classifier = classifier;
        this.botUserMappingService = botUserMappingService;
        this.placeProperties = placeProperties;
        this.handlers = handlers;
    }

    @PostConstruct
    void buildRouter() {
        for (MessageHandler handler : handlers) {
            routerMap.put(handler.supports(), handler);
        }
    }

    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request) {
        ChatbotContext ctx = ChatbotContext.start(placeProperties.search().syncDeadlineMs());
        try {
            String botUserKey = request.userRequest().user().id();
            MessageType type = classifier.classify(request, botUserKey);

            // 미연동 가드: LINK_CODE 외에는 매핑 필요
            if (type != MessageType.LINK_CODE
                    && botUserMappingService.resolveUserId(botUserKey).isEmpty()) {
                return ChatbotV1Dto.SkillResponse.simple("먼저 앱에서 발급한 6자리 연동코드를 보내주세요.");
            }

            MessageHandler handler = routerMap.get(type);
            if (handler == null) {
                log.warn("No handler for messageType={}", type);
                return ChatbotV1Dto.SkillResponse.simple(
                        "장소 등록은 인스타그램 링크를 보내주세요. 연동은 앱에서 발급한 6자리 숫자를 입력하세요."
                );
            }
            return handler.handle(request, ctx);
        } catch (CoreException e) {
            log.warn("Chatbot webhook CoreException : {}", e.getErrorType().getCode(), e);
            return ChatbotV1Dto.SkillResponse.simple(e.getMessage());
        } catch (Exception e) {
            log.error("Chatbot webhook unexpected error", e);
            return ChatbotV1Dto.SkillResponse.simple("일시적인 오류가 발생했어요. 잠시 후 다시 시도해주세요.");
        }
    }
}
