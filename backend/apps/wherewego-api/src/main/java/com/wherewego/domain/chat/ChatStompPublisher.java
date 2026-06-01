package com.wherewego.domain.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * P2: 실시간 STOMP push 발행기(FR-12/13). {@link SimpMessagingTemplate}(SimpleBroker /topic/**) 래퍼이다.
 *
 * <p>BotChatProcessor / CoupleChatService의 트랜잭션 <b>커밋 후(afterCommit)</b>에서만 호출되며,
 * 실시간 push는 단방향 best-effort이므로 발행 실패를 호출자에게 전파하지 않고 {@code log.warn}만 남긴다
 * (저장은 이미 커밋됨, 클라이언트는 REST 재조회로 복구).</p>
 *
 * <p>토픽: {@code /topic/chat/bot/{userId}}, {@code /topic/chat/couple/{groupId}}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStompPublisher {

    private static final String BOT_TOPIC_PREFIX = "/topic/chat/bot/";
    private static final String COUPLE_TOPIC_PREFIX = "/topic/chat/couple/";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 봇 방 토픽({@code /topic/chat/bot/{userId}})으로 프레임을 발행한다. 실패는 best-effort로 무시한다.
     */
    public void publishBot(Long userId, ChatMessageFrame frame) {
        try {
            messagingTemplate.convertAndSend(BOT_TOPIC_PREFIX + userId, frame);
        } catch (Exception e) {
            log.warn("봇 STOMP 발행 실패 (userId={}, messageId={}): {}",
                    userId, frame != null ? frame.messageId() : null, e.getMessage());
        }
    }

    /**
     * 커플 방 토픽({@code /topic/chat/couple/{groupId}})으로 프레임을 발행한다. 실패는 best-effort로 무시한다.
     */
    public void publishCouple(Long groupId, ChatMessageFrame frame) {
        try {
            messagingTemplate.convertAndSend(COUPLE_TOPIC_PREFIX + groupId, frame);
        } catch (Exception e) {
            log.warn("커플 STOMP 발행 실패 (groupId={}, messageId={}): {}",
                    groupId, frame != null ? frame.messageId() : null, e.getMessage());
        }
    }
}
