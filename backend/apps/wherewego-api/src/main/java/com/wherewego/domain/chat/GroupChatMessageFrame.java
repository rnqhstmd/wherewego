package com.wherewego.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * GC-1: 그룹 채팅 메시지 프레임(FR-GC1-4, 설계 D4). 그룹 메시지 페이지 응답 전용 표현으로,
 * 봇/레거시 응답이 쓰는 {@link ChatMessageFrame}을 건드리지 않는다(BR-GC1-1).
 *
 * <p>멀티유저 방이므로 발신자 식별({@code senderUserId} + 배치 조회된 {@code senderNickname})을 포함하고,
 * REEL_LINK 에는 pins 파생 {@code registered} 를 내린다 — 상태 컬럼 없이 어떤 재조회로도 자기치유된다.</p>
 *
 * @param messageId      메시지 PK
 * @param roomId         소속 방 ID
 * @param senderUserId   발신 사용자. 계정 삭제로 NULL 처리된 메시지는 {@code null}.
 * @param senderNickname 발신자 닉네임(서버 배치 조회). 발신자 NULL 이면 {@code null} — 클라가 "(알 수 없음)" 처리.
 * @param kind           메시지 종류(payload 스키마 결정)
 * @param payload        재파싱된 payload 객체(JsonNode). 파싱 불가 시 빈 객체.
 * @param registered     REEL_LINK 만 — 이 릴스의 핀이 그룹에 존재하면 {@code true}. 그 외 kind 는 {@code null}.
 * @param createdAt      ISO8601(offset) 생성 시각 문자열
 */
public record GroupChatMessageFrame(
        Long messageId,
        Long roomId,
        Long senderUserId,
        String senderNickname,
        MessageKind kind,
        Object payload,
        Boolean registered,
        String createdAt
) {

    private static final Logger log = LoggerFactory.getLogger(GroupChatMessageFrame.class);

    /**
     * {@link ChatMessage} 엔티티를 그룹 프레임으로 변환한다({@link ChatMessageFrame#from} 동형 + 발신자/registered).
     */
    public static GroupChatMessageFrame from(ChatMessage message, ObjectMapper objectMapper,
                                             String senderNickname, Boolean registered) {
        return new GroupChatMessageFrame(
                message.getId(),
                message.getRoomId(),
                message.getSenderUserId(),
                senderNickname,
                message.getKind(),
                parsePayload(message.getPayloadJson(), objectMapper),
                registered,
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
            log.warn("GroupChatMessageFrame payload 재파싱 실패, 빈 객체로 폴백: {}", e.getMessage());
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
