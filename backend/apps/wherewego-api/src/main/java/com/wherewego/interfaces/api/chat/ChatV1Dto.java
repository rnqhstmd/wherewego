package com.wherewego.interfaces.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.domain.chat.BotRoomSummary;
import com.wherewego.domain.chat.ChatMessage;
import com.wherewego.domain.chat.ChatMessageFrame;
import com.wherewego.domain.chat.ChatMessagePageResult;
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

    /** 커플 방 메시지 전송 요청(FR-8). */
    public record CoupleMessageRequest(@NotBlank @Size(max = 1000) String text) {
    }

    /**
     * 메시지 전송 응답(봇/커플 공통). 봇 방은 PROCESSING 플레이스홀더 id/kind를 돌려준다.
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
