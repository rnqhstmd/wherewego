package com.wherewego.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * P2: STOMP 실시간 push 프레임(FR-12/13). {@code /topic/chat/bot/{userId}},
 * {@code /topic/chat/couple/{groupId}} 토픽으로 발행되는 메시지 표현이다.
 *
 * <p>{@link ChatMessage}는 {@code payloadJson}을 직렬화된 JSON 문자열(String)로만 보유하지만,
 * 프레임은 클라이언트 소비 편의를 위해 이를 {@link JsonNode} 객체({@code payload})로 재파싱하여 노출한다
 * (supports:jackson {@code ObjectMapper} 재사용).</p>
 *
 * @param messageId  메시지 PK
 * @param roomId     소속 방 ID
 * @param senderType 발신 주체
 * @param kind       메시지 종류(payload 스키마 결정)
 * @param payload    재파싱된 payload 객체(JsonNode). 파싱 불가 시 빈 객체.
 * @param createdAt  ISO8601(offset) 생성 시각 문자열
 */
public record ChatMessageFrame(
        Long messageId,
        Long roomId,
        SenderType senderType,
        MessageKind kind,
        Object payload,
        String createdAt
) {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageFrame.class);

    /**
     * {@link ChatMessage} 엔티티를 STOMP 프레임으로 변환한다.
     *
     * <p>{@code payloadJson}(String)을 {@link ObjectMapper#readTree(String)}로 {@link JsonNode}로 재파싱한다.
     * null/blank이거나 파싱에 실패하면 빈 객체({@code {}})로 방어적으로 폴백한다.</p>
     *
     * @param message      변환 대상 메시지(BaseEntity id/createdAt 포함)
     * @param objectMapper supports:jackson 구성이 적용된 {@code ObjectMapper}
     */
    public static ChatMessageFrame from(ChatMessage message, ObjectMapper objectMapper) {
        return new ChatMessageFrame(
                message.getId(),
                message.getRoomId(),
                message.getSenderType(),
                message.getKind(),
                parsePayload(message.getPayloadJson(), objectMapper),
                formatCreatedAt(message.getCreatedAt())
        );
    }

    private static JsonNode parsePayload(String payloadJson, ObjectMapper objectMapper) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            log.warn("ChatMessageFrame payload 재파싱 실패, 빈 객체로 폴백: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private static String formatCreatedAt(ZonedDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
