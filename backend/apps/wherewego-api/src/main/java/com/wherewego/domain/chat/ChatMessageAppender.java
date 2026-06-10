package com.wherewego.domain.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.domain.chat.BotPlaceCardsPayloadBuilder.PlaceCardsPayload;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * P2: senderType/kind 조합별 {@link ChatMessage} 팩토리 + payload JSONB 직렬화를 한 곳에 집약한다.
 *
 * <p>설계상 payload 객체 → JSON 문자열 변환은 도메인 엔티티 밖(이 Appender)이 전담한다
 * ({@link ChatMessage}/{@code BaseEntity}의 {@code ObjectMapper} 의존 회피). 3개 호출자
 * (BotChatService/BotChatProcessor/CoupleChatService)의 중복을 제거하는 stateless 헬퍼이며,
 * <b>트랜잭션 경계는 호출자가 소유</b>한다(자체 {@code @Transactional} 없이 호출자 트랜잭션에 합류).</p>
 *
 * <p>각 append 메서드는 {@link ChatMessage#create} → {@link ChatMessageRepository#save}를 거쳐
 * 저장된 {@link ChatMessage}를 반환한다. payload 직렬화 실패는 내부 처리 오류로 보고
 * {@link ErrorType#INTERNAL_ERROR}로 변환한다.</p>
 */
@Component
@RequiredArgsConstructor
public class ChatMessageAppender {

    /** payload 없는 메시지(PROCESSING)의 빈 JSON 객체. */
    private static final String EMPTY_PAYLOAD = "{}";

    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    /**
     * 사용자 텍스트 메시지(봇 방). kind=TEXT, senderType=USER.
     *
     * @param roomId 소속 방 ID
     * @param userId 발신 사용자 ID(sender_user_id)
     * @param text   본문. payload {@code {"text": text}}로 직렬화된다.
     */
    public ChatMessage appendUserText(Long roomId, Long userId, String text) {
        return save(roomId, SenderType.USER, userId, MessageKind.TEXT, new TextPayload(text));
    }

    /**
     * 봇 처리중 플레이스홀더. kind=PROCESSING, senderType=BOT, senderUserId=null, payload={@code {}}.
     */
    public ChatMessage appendBotProcessing(Long roomId) {
        return saveRaw(roomId, SenderType.BOT, null, MessageKind.PROCESSING, EMPTY_PAYLOAD);
    }

    /**
     * 봇 장소 추출 결과 카드. kind=PLACE_CARDS, senderType=BOT, senderUserId=null.
     *
     * @param payload {@link BotPlaceCardsPayloadBuilder}가 생성한 PLACE_CARDS payload
     */
    public ChatMessage appendBotPlaceCards(Long roomId, PlaceCardsPayload payload) {
        return save(roomId, SenderType.BOT, null, MessageKind.PLACE_CARDS, payload);
    }

    /**
     * 봇 시스템 안내/오류 메시지. kind=SYSTEM, senderType=BOT, senderUserId=null.
     *
     * @param text 본문. payload {@code {"text": text}}로 직렬화된다.
     */
    public ChatMessage appendBotSystem(Long roomId, String text) {
        return save(roomId, SenderType.BOT, null, MessageKind.SYSTEM, new TextPayload(text));
    }

    /**
     * 그룹 방 사용자 텍스트 메시지(GC-1: 커플 방 일반화). kind=TEXT, senderType=USER.
     *
     * <p>봇 방 사용자 텍스트와 payload 형태가 동일하므로 {@link #appendUserText}를 재사용한다(의미 구분용 별칭).</p>
     */
    public ChatMessage appendGroupText(Long roomId, Long userId, String text) {
        return appendUserText(roomId, userId, text);
    }

    /**
     * 그룹 방 릴스 링크 메시지(GC-1, FR-GC1-3). kind=REEL_LINK, senderType=USER.
     *
     * @param url 검증된 인스타 릴스 URL. payload {@code {"url": url, "thumbnailKey": null}}로 직렬화된다
     *            (thumbnailKey 는 GC-3 썸네일 단계에서 채워질 예약 필드).
     */
    public ChatMessage appendReelLink(Long roomId, Long userId, String url) {
        return save(roomId, SenderType.USER, userId, MessageKind.REEL_LINK, new ReelLinkPayload(url, null));
    }

    private ChatMessage save(Long roomId, SenderType senderType, Long senderUserId,
                             MessageKind kind, Object payload) {
        return saveRaw(roomId, senderType, senderUserId, kind, serialize(payload));
    }

    private ChatMessage saveRaw(Long roomId, SenderType senderType, Long senderUserId,
                                MessageKind kind, String payloadJson) {
        ChatMessage message = ChatMessage.create(roomId, senderType, senderUserId, kind, payloadJson);
        return chatMessageRepository.save(message);
    }

    /**
     * payload 객체 → JSON 문자열 직렬화를 캡슐화한다. 실패 시 {@link ErrorType#INTERNAL_ERROR}로 변환한다.
     */
    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "채팅 메시지 payload 직렬화에 실패했습니다.");
        }
    }

    /**
     * 텍스트 payload 루트. {@code {"text": ...}} JSON 객체로 직렬화된다(이스케이프된 문자열이 아닌 정상 객체).
     *
     * @param text 메시지 본문
     */
    private record TextPayload(String text) { }

    /**
     * 릴스 링크 payload 루트(GC-1). {@code {"url": ..., "thumbnailKey": null}} JSON 객체로 직렬화된다.
     *
     * @param url          인스타 릴스 URL
     * @param thumbnailKey S3 썸네일 키(GC-3 예약 — GC-1 에서는 항상 {@code null})
     */
    private record ReelLinkPayload(String url, String thumbnailKey) { }
}
