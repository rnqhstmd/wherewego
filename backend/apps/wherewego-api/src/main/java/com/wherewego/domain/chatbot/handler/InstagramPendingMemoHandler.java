package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.PendingInstagramAutoSaveScheduler;
import com.wherewego.domain.chatbot.PendingInstagramSession;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 인스타 URL 직후의 메모 입력 메시지 처리.
 *
 * <p>분기 (취소 분기 제거됨 — 링크 보낸 이상 저장은 반드시 일어남):
 * <ul>
 *   <li>{@code "메모 없이 저장"} 정확 매칭 → memo=null로 candidates 처리</li>
 *   <li>그 외 텍스트 → 그 텍스트를 메모로 사용해서 candidates 처리</li>
 * </ul>
 *
 * <p>처리 진입 시점에 {@code AutoSaveScheduler.cancel(botUserKey)}를 호출하여
 * TTL 만료 시 자동 저장이 중복 트리거되지 않도록 한다. cancel(false)이므로
 * 이미 실행 중인 자동 저장은 중단되지 않으며, DB UNIQUE 제약이 최종 가드 역할을 한다.</p>
 *
 * <p>실제 candidates 처리는 {@link InstagramLinkHandler#processWithMemoAsync}에 위임.</p>
 */
@Component
@RequiredArgsConstructor
public class InstagramPendingMemoHandler implements MessageHandler {

    /** "메모 없이 저장" 정확 매칭 텍스트. QuickReply 버튼의 전송값과 일치해야 한다. */
    static final String SAVE_WITHOUT_MEMO_TRIGGER = "메모 없이 저장";

    private final PendingInstagramSession pendingInstagramSession;
    private final PendingInstagramAutoSaveScheduler autoSaveScheduler;
    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final InstagramLinkHandler instagramLinkHandler;

    @Override
    public MessageType supports() {
        return MessageType.INSTAGRAM_PENDING_MEMO;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        Optional<String> pendingOpt = pendingInstagramSession.peek(botUserKey);
        if (pendingOpt.isEmpty()) {
            // 동시성으로 pending이 사라진 경우 — 일반 안내로 fallback.
            return ChatbotV1Dto.SkillResponse.simple(
                    "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요.");
        }
        String instagramUrl = pendingOpt.get();

        // 사용자가 응답을 보냈으므로 TTL 자동 저장 task는 더 이상 필요 없음.
        autoSaveScheduler.cancel(botUserKey);

        String utterance = request.userRequest().utterance() == null
                ? ""
                : request.userRequest().utterance().trim();

        // 메모 결정 — "메모 없이 저장" 정확 매칭만 memo=null, 그 외 텍스트는 메모로.
        String memo = SAVE_WITHOUT_MEMO_TRIGGER.equals(utterance) ? null : utterance;

        // userId / groupId 가드 (방어적).
        Long userId = ctx.userId();
        if (userId == null) {
            Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
            if (userIdOpt.isEmpty()) {
                pendingInstagramSession.invalidate(botUserKey);
                return ChatbotV1Dto.SkillResponse.simple(
                        "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러주세요.");
            }
            userId = userIdOpt.get();
        }
        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            pendingInstagramSession.invalidate(botUserKey);
            return ChatbotV1Dto.SkillResponse.simple("그룹에 먼저 참여해주세요.");
        }

        // pending은 처리 시작 시점에 invalidate (실패해도 같은 URL 재시도는 사용자가 새로 보내야 함).
        pendingInstagramSession.invalidate(botUserKey);

        String callbackUrl = request.userRequest().callbackUrl();
        return instagramLinkHandler.processWithMemoAsync(
                botUserKey, userId, groupIdOpt.get(), instagramUrl, memo, callbackUrl);
    }
}
