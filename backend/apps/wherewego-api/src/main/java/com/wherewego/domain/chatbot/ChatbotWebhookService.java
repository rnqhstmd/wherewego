package com.wherewego.domain.chatbot;

import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.handler.MessageHandler;
import com.wherewego.domain.chatbot.handler.ReelMemoWaitingHandler;
import com.wherewego.domain.chatbot.handler.ReelSingleWantHandler;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.notification.NotificationService;
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
 *
 * <p>Phase 12 가드(§8.5):
 * <ul>
 *     <li>새 URL 도착 시 활성 {@link ReelSavedSelectionSession} 이 있으면 현재 선택 기준으로 자동 저장 후 새 PROCESSING 시작 (EC-U)</li>
 *     <li>MULTI_SELECTING/MEMO_WAITING 중 룰렛/공유 액션(PLACE_SELECTION 등) → "지금은 릴스 처리 중" 안내 + 세션 유지 (D-7)</li>
 * </ul></p>
 */
@Service
public class ChatbotWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotWebhookService.class);

    private final MessageClassifier classifier;
    private final BotUserMappingService botUserMappingService;
    private final PlaceProperties placeProperties;
    private final PendingNotificationSession pendingNotificationSession;
    private final ReelSavedSelectionSession reelSavedSelectionSession;
    private final GroupMemberService groupMemberService;
    private final GroupMemberRepository groupMemberRepository;
    private final ReelMemoWaitingHandler reelMemoWaitingHandler;
    private final NotificationService notificationService;
    private final List<MessageHandler> handlers;

    private final Map<MessageType, MessageHandler> routerMap = new EnumMap<>(MessageType.class);

    public ChatbotWebhookService(MessageClassifier classifier,
                                 BotUserMappingService botUserMappingService,
                                 PlaceProperties placeProperties,
                                 PendingNotificationSession pendingNotificationSession,
                                 ReelSavedSelectionSession reelSavedSelectionSession,
                                 GroupMemberService groupMemberService,
                                 GroupMemberRepository groupMemberRepository,
                                 ReelMemoWaitingHandler reelMemoWaitingHandler,
                                 NotificationService notificationService,
                                 List<MessageHandler> handlers) {
        this.classifier = classifier;
        this.botUserMappingService = botUserMappingService;
        this.placeProperties = placeProperties;
        this.pendingNotificationSession = pendingNotificationSession;
        this.reelSavedSelectionSession = reelSavedSelectionSession;
        this.groupMemberService = groupMemberService;
        this.groupMemberRepository = groupMemberRepository;
        this.reelMemoWaitingHandler = reelMemoWaitingHandler;
        this.notificationService = notificationService;
        this.handlers = handlers;
    }

    @PostConstruct
    void buildRouter() {
        MessageHandler singleWantHandler = null;
        for (MessageHandler handler : handlers) {
            routerMap.put(handler.supports(), handler);
            if (handler instanceof ReelSingleWantHandler) {
                singleWantHandler = handler;
            }
        }
        // SINGLE_WANT_NO 는 별도 enum 이지만 동일한 ReelSingleWantHandler 인스턴스가 처리한다.
        if (singleWantHandler != null) {
            routerMap.put(MessageType.SINGLE_WANT_NO, singleWantHandler);
        }
    }

    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request) {
        ChatbotContext ctx = ChatbotContext.start(placeProperties.search().syncDeadlineMs());
        String botUserKey = request.userRequest().user().id();
        try {
            MessageType type = classifier.classify(request, botUserKey);

            // 미연동 가드: 실제로 매핑이 필요한 핸들러에만 적용.
            if (requiresMembership(type)) {
                Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
                if (userIdOpt.isEmpty()) {
                    return decorate(botUserKey, ChatbotV1Dto.SkillResponse.simple(
                            "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러 앱에서 발급한 코드를 입력해주세요.",
                            menuQuickReplies()
                    ));
                }
                ctx.setUserId(userIdOpt.get());
            }

            // Phase 12 가드 1: 룰렛/공유 액션이 활성 릴스 선택/메모 세션과 충돌 시 거부 (D-7).
            if (type == MessageType.PLACE_SELECTION) {
                Optional<ReelSavedSelectionSession.Snapshot> activeOpt =
                        reelSavedSelectionSession.peek(botUserKey);
                if (activeOpt.isPresent()) {
                    ReelSavedSelectionSession.State state = activeOpt.get().state();
                    if (state == ReelSavedSelectionSession.State.MULTI_SELECTING
                            || state == ReelSavedSelectionSession.State.MEMO_WAITING) {
                        return decorate(botUserKey, ChatbotV1Dto.SkillResponse.simple(
                                "지금은 릴스 장소 처리 중이에요. 끝나면 다시 시도해주세요."));
                    }
                }
            }

            // Phase 12 가드 2: 새 URL 도착 시 활성 세션을 현재 선택 기준으로 자동 저장 후 새 PROCESSING 진입 (EC-U).
            if (type == MessageType.INSTAGRAM_LINK) {
                autoSavePreviousIfActive(botUserKey, ctx);
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
     * 새 URL 도착 시 활성 릴스 세션이 있으면 현재 선택(또는 SINGLE_WANT 단계 의도)을 기준으로 즉시 저장한다.
     * 저장 후 결과는 {@link PendingNotificationSession} 에 prepend 메시지로 적재되어 다음 응답에 합쳐진다.
     */
    private void autoSavePreviousIfActive(String botUserKey, ChatbotContext ctx) {
        Optional<ReelSavedSelectionSession.Snapshot> activeOpt =
                reelSavedSelectionSession.peek(botUserKey);
        if (activeOpt.isEmpty()) {
            return;
        }
        ReelSavedSelectionSession.Snapshot snapshot = activeOpt.get();
        Long userId = ctx.userId();
        if (userId == null) {
            return;
        }
        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            return;
        }
        Long groupId = groupIdOpt.get();
        int activeMemberCount = (int) groupMemberRepository.countActiveByGroupId(groupId);

        // SINGLE_WANT / MULTI_SELECTING / BULK_SAVE / MEMO_WAITING 모두 보수적으로 처리:
        // selectedIndices 가 비어 있으면 전체 인덱스로 자동 저장.
        ReelSavedSelectionSession.Snapshot effective = ensureSelectionFilled(snapshot);

        try {
            ReelMemoWaitingHandler.SaveResult result = reelMemoWaitingHandler.saveAllSelected(
                    userId, groupId, effective, effective.pendingMemo(), activeMemberCount);
            if (!result.savedPinIds().isEmpty()) {
                try {
                    notificationService.createForChatbotBatch(groupId, userId, result.savedPinIds());
                } catch (RuntimeException e) {
                    log.warn("notification (auto-save previous) failed groupId={} pinCount={}",
                            groupId, result.savedPinIds().size(), e);
                }
                pendingNotificationSession.put(botUserKey,
                        "📌 이전에 보낸 링크는 " + result.savedPinIds().size() + "곳이 자동 저장되었어요");
            }
        } catch (RuntimeException e) {
            log.warn("auto-save previous session failed botUserKey={} cause={}",
                    botUserKey, e.getMessage(), e);
        } finally {
            reelSavedSelectionSession.invalidate(botUserKey);
        }
    }

    /** selectedIndices 가 비어 있으면 전체 인덱스로 채운 새 snapshot 반환. */
    private static ReelSavedSelectionSession.Snapshot ensureSelectionFilled(
            ReelSavedSelectionSession.Snapshot snapshot) {
        if (!snapshot.selectedIndices().isEmpty()) {
            return snapshot;
        }
        int total = snapshot.places().size();
        java.util.HashSet<Integer> all = new java.util.HashSet<>();
        for (int i = 1; i <= total; i++) {
            all.add(i);
        }
        return new ReelSavedSelectionSession.Snapshot(
                snapshot.state(),
                snapshot.instagramUrl(),
                snapshot.places(),
                all,
                snapshot.wantOnSelected(),
                snapshot.expiresAt(),
                snapshot.pendingMemo()
        );
    }

    private static boolean requiresMembership(MessageType type) {
        return type == MessageType.INSTAGRAM_LINK
                || type == MessageType.PLACE_SELECTION
                || type == MessageType.SINGLE_WANT_YES
                || type == MessageType.SINGLE_WANT_NO
                || type == MessageType.REEL_PLACE_SELECTION
                || type == MessageType.REEL_MEMO_WAITING;
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
