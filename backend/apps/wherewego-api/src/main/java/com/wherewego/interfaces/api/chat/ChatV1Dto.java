package com.wherewego.interfaces.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.domain.chat.ChatMessage;
import com.wherewego.domain.chat.ChatMessageFrame;
import com.wherewego.domain.chat.ChatMessagePageResult;
import com.wherewego.domain.chat.MessageKind;
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
     */
    public record MessagesResponse(
            List<ChatMessageFrame> messages,
            boolean hasMore,
            Long nextCursor
    ) {
        public static MessagesResponse from(ChatMessagePageResult result, ObjectMapper objectMapper) {
            return new MessagesResponse(
                    result.messages().stream()
                            .map(message -> ChatMessageFrame.from(message, objectMapper))
                            .toList(),
                    result.hasMore(),
                    result.nextCursor()
            );
        }
    }
}
