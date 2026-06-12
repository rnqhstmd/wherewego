package com.wherewego.interfaces.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.domain.chat.BotRoomSummary;
import com.wherewego.domain.chat.ChatMessage;
import com.wherewego.domain.chat.ChatMessageFrame;
import com.wherewego.domain.chat.ChatMessagePageResult;
import com.wherewego.domain.chat.GroupChatMessageFrame;
import com.wherewego.domain.chat.GroupMessagesPage;
import com.wherewego.domain.chat.GroupRoomSummary;
import com.wherewego.domain.chat.MessageKind;
import com.wherewego.domain.chat.SenderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * P2 PR-1: 앱 채팅 REST 요청/응답 DTO(FR-4/5/8/9).
 */
public final class ChatV1Dto {

    private ChatV1Dto() {
    }

    /** 봇 방 메시지 전송 요청(FR-4). */
    public record BotMessageRequest(@NotBlank @Size(max = 2000) String text) {
    }

    /**
     * 메시지 전송 응답(봇/그룹 공통). 봇 방은 PROCESSING 플레이스홀더 id/kind를 돌려준다.
     */
    public record SendMessageResponse(Long messageId, MessageKind kind) {
        public static SendMessageResponse from(ChatMessage message) {
            return new SendMessageResponse(message.getId(), message.getKind());
        }
    }

    /**
     * cursor 페이지 메시지 목록 응답(FR-5/9). 메시지는 최신순(id DESC)이며,
     * {@code payload}는 {@link ChatMessageFrame}이 payload_json을 JSON 객체로 재파싱하여 노출한다.
     *
     * <p>GM-2: {@code groupId}를 노출한다 — 봇 방 응답에서 iOS 가 릴스 저장 그룹으로 사용한다(FR-6/AC-6).
     * 커플 방 등 groupId 가 의미 없는 응답은 {@code null}로 매핑한다.</p>
     */
    public record MessagesResponse(
            Long groupId,
            List<ChatMessageFrame> messages,
            boolean hasMore,
            Long nextCursor
    ) {
        public static MessagesResponse from(ChatMessagePageResult result, ObjectMapper objectMapper) {
            return from(null, result, objectMapper);
        }

        public static MessagesResponse from(Long groupId, ChatMessagePageResult result, ObjectMapper objectMapper) {
            return new MessagesResponse(
                    groupId,
                    result.messages().stream()
                            .map(message -> ChatMessageFrame.from(message, objectMapper))
                            .toList(),
                    result.hasMore(),
                    result.nextCursor()
            );
        }
    }

    /**
     * GC-1: 그룹 메시지 전송 요청(FR-GC1-3). kind 분기 — TEXT 는 {@code text}(1~2000자),
     * REEL_LINK 는 {@code url}(https + 인스타 패턴), PIN_REPLY 는 {@code text}(1~2000자) + {@code pinId}
     * (그룹 활성 핀)만 사용한다. kind 조건부 검증은 서비스가 수행한다
     * (CHAT_KIND_INVALID / CHAT_TEXT_INVALID / CHAT_REEL_URL_INVALID / CHAT_PIN_INVALID 400).
     *
     * <p>{@code pinId} 는 PIN_REPLY 외 kind 에서는 무시된다(nullable additive 필드).</p>
     */
    public record GroupMessageRequest(MessageKind kind, String text, String url, Long pinId) {
    }

    /**
     * GC-1: 그룹 메시지 페이지 응답(FR-GC1-4). 프레임은 {@link GroupChatMessageFrame} —
     * 발신자(senderUserId/senderNickname)와 REEL_LINK {@code registered}(pins 파생)가 합성되어 있다.
     */
    public record GroupMessagesResponse(
            Long groupId,
            List<GroupChatMessageFrame> messages,
            boolean hasMore,
            Long nextCursor,
            Long lastReadMessageId
    ) {
        public static GroupMessagesResponse from(Long groupId, GroupMessagesPage page) {
            return new GroupMessagesResponse(
                    groupId, page.frames(), page.hasMore(), page.nextCursor(), page.lastReadMessageId());
        }
    }

    /**
     * GC-1: 그룹 채팅방 목록 항목 응답(FR-GC1-7). 도메인 {@link GroupRoomSummary}를 그대로 노출한다.
     * 방이 없는 활성 그룹은 가상 항목(roomId/lastPreview/lastSenderUserId/lastAt=null, hasUnread=false)이다.
     */
    public record GroupRoomSummaryResponse(
            Long roomId,
            Long groupId,
            String groupName,
            String lastPreview,
            Long lastSenderUserId,
            String lastSenderNickname,
            boolean hasUnread,
            int unreadCount,
            String lastAt
    ) {
        public static GroupRoomSummaryResponse from(GroupRoomSummary summary) {
            return new GroupRoomSummaryResponse(
                    summary.roomId(),
                    summary.groupId(),
                    summary.groupName(),
                    summary.lastPreview(),
                    summary.lastSenderUserId(),
                    summary.lastSenderNickname(),
                    summary.hasUnread(),
                    summary.unreadCount(),
                    summary.lastAt()
            );
        }
    }

    /**
     * GM-2: DM 목록 항목 응답(FR-2/FR-6, AC-2/AC-6/AC-7). 도메인 {@link BotRoomSummary}를 그대로 노출한다.
     * 봇 방이 없는 활성 그룹은 가상 항목(roomId/lastPreview/lastSenderType/lastAt=null, unread=false)이다.
     */
    public record BotRoomSummaryResponse(
            Long roomId,
            Long groupId,
            String groupName,
            String lastPreview,
            SenderType lastSenderType,
            boolean unread,
            String lastAt
    ) {
        public static BotRoomSummaryResponse from(BotRoomSummary summary) {
            return new BotRoomSummaryResponse(
                    summary.roomId(),
                    summary.groupId(),
                    summary.groupName(),
                    summary.lastPreview(),
                    summary.lastSenderType(),
                    summary.unread(),
                    summary.lastAt()
            );
        }
    }
}
