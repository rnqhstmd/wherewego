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
import java.util.Optional;

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

            // 미연동 가드: 실제로 매핑이 필요한 핸들러(INSTAGRAM_LINK, PLACE_SELECTION)에만 적용.
            // UNKNOWN/TEXT_2SEC_CANDIDATE 는 가드 없이 라우터로 보내 불필요한 DB 조회를 피한다.
            // 조회한 userId 는 ctx 에 캐싱하여 핸들러에서 중복 조회를 방지한다.
            if (type == MessageType.INSTAGRAM_LINK || type == MessageType.PLACE_SELECTION) {
                Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
                if (userIdOpt.isEmpty()) {
                    return ChatbotV1Dto.SkillResponse.simple(
                            "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러 앱에서 발급한 코드를 입력해주세요.",
                            menuQuickReplies()
                    );
                }
                ctx.setUserId(userIdOpt.get());
            }

            MessageHandler handler = routerMap.get(type);
            if (handler == null) {
                log.warn("No handler for messageType={}", type);
                return ChatbotV1Dto.SkillResponse.simple(
                        "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요. 그룹 연동이 필요하면 아래 [🔗 그룹 연동하기]를 눌러주세요.",
                        menuQuickReplies()
                );
            }
            return handler.handle(request, ctx);
        } catch (CoreException e) {
            log.warn("Chatbot webhook CoreException : {}", e.getErrorType().getCode(), e);
            return ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e));
        } catch (Exception e) {
            log.error("Chatbot webhook unexpected error", e);
            return ChatbotV1Dto.SkillResponse.simple("일시적인 오류가 발생했어요. 잠시 후 다시 시도해주세요.");
        }
    }

    /**
     * 폴백/안내 응답 하단에 노출되는 기본 빠른 답장.
     * blockId는 카카오 i 오픈빌더에서 "그룹 연동" 블록을 만든 뒤 해당 ID를 messageText 폴백으로 사용.
     * 사용자가 i 오픈빌더에서 "그룹 연동하기"라는 발화 패턴을 해당 블록에 연결하면 자동 라우팅된다.
     */
    private static java.util.List<ChatbotV1Dto.QuickReply> menuQuickReplies() {
        return java.util.List.of(
                ChatbotV1Dto.QuickReply.message("🔗 그룹 연동하기", "그룹 연동하기")
        );
    }
}
