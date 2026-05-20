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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill webhook 진입 서비스. {@code t0} 캡처 → 분류 → 핸들러 위임 → 폴백 try-catch 안전망.
 *
 * <p>모든 응답은 {@link #decorate}를 통과시켜 {@link PendingNotificationSession}에 적재된
 * 자동 저장 알림이 있으면 본문 앞에 1회 prepend 후 소비한다. 단 {@code useCallback=true} 응답에는
 * prepend를 적용하지 않는다 — 진짜 응답은 비동기 callback push로 가기 때문이며, 본 응답을
 * 자동 저장 알림으로 오염시키지 않기 위함.</p>
 */
@Service
public class ChatbotWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotWebhookService.class);

    private final MessageClassifier classifier;
    private final BotUserMappingService botUserMappingService;
    private final PlaceProperties placeProperties;
    private final PendingNotificationSession pendingNotificationSession;
    private final List<MessageHandler> handlers;

    private final Map<MessageType, MessageHandler> routerMap = new EnumMap<>(MessageType.class);

    public ChatbotWebhookService(MessageClassifier classifier,
                                 BotUserMappingService botUserMappingService,
                                 PlaceProperties placeProperties,
                                 PendingNotificationSession pendingNotificationSession,
                                 List<MessageHandler> handlers) {
        this.classifier = classifier;
        this.botUserMappingService = botUserMappingService;
        this.placeProperties = placeProperties;
        this.pendingNotificationSession = pendingNotificationSession;
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
        String botUserKey = request.userRequest().user().id();
        try {
            MessageType type = classifier.classify(request, botUserKey);

            // 미연동 가드: 실제로 매핑이 필요한 핸들러(INSTAGRAM_LINK, PLACE_SELECTION, PENDING_MEMO)에만 적용.
            if (type == MessageType.INSTAGRAM_LINK
                    || type == MessageType.PLACE_SELECTION
                    || type == MessageType.INSTAGRAM_PENDING_MEMO) {
                Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
                if (userIdOpt.isEmpty()) {
                    return decorate(botUserKey, ChatbotV1Dto.SkillResponse.simple(
                            "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러 앱에서 발급한 코드를 입력해주세요.",
                            menuQuickReplies()
                    ));
                }
                ctx.setUserId(userIdOpt.get());
            }

            MessageHandler handler = routerMap.get(type);
            if (handler == null) {
                log.warn("No handler for messageType={}", type);
                return decorate(botUserKey, ChatbotV1Dto.SkillResponse.simple(
                        "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요. 그룹 연동이 필요하면 아래 [🔗 그룹 연동하기]를 눌러주세요.",
                        menuQuickReplies()
                ));
            }
            return decorate(botUserKey, handler.handle(request, ctx));
        } catch (CoreException e) {
            log.warn("Chatbot webhook CoreException : {}", e.getErrorType().getCode(), e);
            return decorate(botUserKey, ChatbotV1Dto.SkillResponse.simple(ChatbotErrorMessages.userMessage(e)));
        } catch (Exception e) {
            log.error("Chatbot webhook unexpected error", e);
            return decorate(botUserKey, ChatbotV1Dto.SkillResponse.simple(
                    "일시적인 오류가 발생했어요. 잠시 후 다시 시도해주세요."));
        }
    }

    /**
     * PendingNotificationSession에 적재된 자동 저장 알림이 있으면 응답 본문 앞에 prepend + invalidate.
     * useCallback=true 응답엔 prepend를 적용하지 않는다 (진짜 응답은 callback push에서 오므로).
     */
    private ChatbotV1Dto.SkillResponse decorate(String botUserKey, ChatbotV1Dto.SkillResponse resp) {
        if (Boolean.TRUE.equals(resp.useCallback())) {
            return resp;
        }
        Optional<String> noticeOpt = pendingNotificationSession.peek(botUserKey);
        if (noticeOpt.isEmpty()) {
            return resp;
        }
        pendingNotificationSession.invalidate(botUserKey);
        return prependSimpleText(noticeOpt.get(), resp);
    }

    /**
     * 기존 응답의 outputs 앞에 simpleText 1개를 삽입한 새 SkillResponse를 만든다.
     * 기존 quickReplies/useCallback은 그대로 보존. cards 응답에도 동일 패턴.
     */
    private static ChatbotV1Dto.SkillResponse prependSimpleText(String prefixText,
                                                                ChatbotV1Dto.SkillResponse resp) {
        Map<String, Object> prefix = new LinkedHashMap<>();
        Map<String, Object> simpleText = new LinkedHashMap<>();
        simpleText.put("text", prefixText);
        prefix.put("simpleText", simpleText);

        List<Map<String, Object>> existing = (resp.template() != null && resp.template().outputs() != null)
                ? resp.template().outputs()
                : List.of();
        List<Map<String, Object>> merged = new ArrayList<>(existing.size() + 1);
        merged.add(prefix);
        merged.addAll(existing);

        List<ChatbotV1Dto.QuickReply> quickReplies =
                resp.template() != null ? resp.template().quickReplies() : null;
        return ChatbotV1Dto.SkillResponse.cards(merged, quickReplies);
    }

    /**
     * 폴백/안내 응답 하단에 노출되는 기본 빠른 답장.
     * blockId 대신 messageText 발화로 라우팅 — 카카오 i 오픈빌더에서 발화 패턴이 그룹 연결 블록에 매칭됨.
     */
    private static List<ChatbotV1Dto.QuickReply> menuQuickReplies() {
        return List.of(
                ChatbotV1Dto.QuickReply.message("🔗 그룹 연동하기", "그룹 연동하기")
        );
    }
}
