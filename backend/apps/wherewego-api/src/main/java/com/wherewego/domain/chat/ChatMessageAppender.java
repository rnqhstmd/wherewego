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
     * @param url 검증된 인스타 릴스 URL. payload {@code {"url": url, "thumbnailKey": null, "thumbnailUrl": null}}로
     *            직렬화된다(thumbnailUrl 은 GC-3 비동기 스크래핑이 채우며, thumbnailKey 는 S3 전환용 예약 필드).
     */
    public ChatMessage appendReelLink(Long roomId, Long userId, String url) {
        return save(roomId, SenderType.USER, userId, MessageKind.REEL_LINK, new ReelLinkPayload(url, null, null));
    }

    /**
     * 그룹 방 핀 답장 메시지(지도 핀 말풍선 "답장"). kind=PIN_REPLY, senderType=USER.
     *
     * <p>payload {@code {"pinId": ..., "text": ...}} 로 직렬화된다. 핀 카드는 조회 시 {@code pinId} 로
     * 배치 조회하여 프레임에 합성(자기치유)하므로 payload 에는 식별자만 보존한다 — REEL_LINK 의 url 보존과 동형.</p>
     *
     * @param pinId 답장 대상 핀 ID(전송 시 그룹 활성 핀으로 검증됨)
     * @param text  답장 본문(1~2000자 — 호출 전 서비스에서 검증)
     */
    public ChatMessage appendGroupPinReply(Long roomId, Long userId, Long pinId, String text) {
        return save(roomId, SenderType.USER, userId, MessageKind.PIN_REPLY, new PinReplyPayload(pinId, text));
    }

    /**
     * 방문 체크인 카드(정책 v2 — 서버가 방문자 명의로 자동 적재). kind=PIN_VISIT, senderType=USER.
     *
     * <p>payload {@code {"pinId": ...}} 로 직렬화된다(PIN_REPLY 와 동형 — 핀 카드는 조회 시 pinId 배치 조회로 합성).</p>
     *
     * @param pinId 방문 대상 핀 ID(전송 전 서비스가 그룹 활성 핀으로 검증함)
     */
    public ChatMessage appendGroupPinVisit(Long roomId, Long userId, Long pinId) {
        return save(roomId, SenderType.USER, userId, MessageKind.PIN_VISIT, new PinVisitPayload(pinId));
    }

    /**
     * 추억 전환 카드(정책 v2 — 서버가 방문자 명의로 자동 적재). kind=PIN_MEMORY, senderType=USER.
     *
     * <p>payload {@code {"pinId": ..., "userIds": [...]}} 로 직렬화된다. {@code userIds} 는 그때 참여 명단
     * 스냅샷이며(현재 pin_visits 상태가 아니라 payload 사용), 조회 시 top-level visitParticipants 합성에 쓰인다.</p>
     *
     * @param pinId   방문 대상 핀 ID(전송 전 서비스가 검증함)
     * @param userIds 참여 명단 스냅샷(본인 + 동행)
     */
    public ChatMessage appendGroupPinMemory(Long roomId, Long userId, Long pinId, java.util.List<Long> userIds) {
        return save(roomId, SenderType.USER, userId, MessageKind.PIN_MEMORY, new PinMemoryPayload(pinId, userIds));
    }

    /**
     * REEL_LINK payload 를 썸네일 URL 포함하여 JSON 문자열로 직렬화한다(GC-3, FR-GC3-2). 저장은 하지 않으며
     * {@link ReelThumbnailWriter}가 비동기 스크래핑 결과를 {@link ChatMessage#replacePayloadJson}로 반영할 때 쓴다.
     * thumbnailKey 는 S3 전환용 예약 슬롯이라 계속 {@code null} 로 둔다.
     *
     * @param url          기존 payload 에서 읽어온 릴스 URL(보존)
     * @param thumbnailUrl 스크래핑한 og:image URL
     */
    public String serializeReelLinkPayload(String url, String thumbnailUrl) {
        return serialize(new ReelLinkPayload(url, null, thumbnailUrl));
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
     * 릴스 링크 payload 루트(GC-1/GC-3). {@code {"url": ..., "thumbnailKey": null, "thumbnailUrl": ...}} JSON 객체로
     * 직렬화된다.
     *
     * @param url          인스타 릴스 URL
     * @param thumbnailKey S3 썸네일 키(예약 — 현행 항상 {@code null}, MVP 직참조라 미사용)
     * @param thumbnailUrl og:image 직참조 썸네일 URL(GC-3, FR-GC3-2 — 전송 시 {@code null}, 비동기 스크래핑 성공 시 채움)
     */
    private record ReelLinkPayload(String url, String thumbnailKey, String thumbnailUrl) { }

    /**
     * 핀 답장 payload 루트. {@code {"pinId": ..., "text": ...}} JSON 객체로 직렬화된다.
     * 핀 카드 메타(장소명/태그/사진)는 조회 시 {@code pinId} 로 배치 조회하여 합성하므로
     * 여기에는 식별자와 본문만 보존한다(REEL_LINK 의 url 보존과 동형 — 핀 변경/삭제에 자기치유).
     *
     * @param pinId 답장 대상 핀 ID
     * @param text  답장 본문
     */
    private record PinReplyPayload(Long pinId, String text) { }

    /**
     * 체크인 카드 payload 루트(정책 v2). {@code {"pinId": ...}} JSON 객체로 직렬화된다.
     * 핀 카드 메타는 PIN_REPLY 와 동일하게 조회 시 pinId 배치 조회로 합성한다(식별자만 보존).
     *
     * @param pinId 방문 대상 핀 ID
     */
    private record PinVisitPayload(Long pinId) { }

    /**
     * 추억 카드 payload 루트(정책 v2). {@code {"pinId": ..., "userIds": [...]}} JSON 객체로 직렬화된다.
     * {@code userIds} 는 그때 참여 명단 스냅샷이며 조회 시 top-level visitParticipants 합성에 쓰인다
     * (현재 pin_visits 상태가 아니라 payload 보존값 사용 — "그때 누구와의 추억" 고정).
     *
     * @param pinId   방문 대상 핀 ID
     * @param userIds 참여 명단 스냅샷(본인 + 동행)
     */
    private record PinMemoryPayload(Long pinId, java.util.List<Long> userIds) { }
}
