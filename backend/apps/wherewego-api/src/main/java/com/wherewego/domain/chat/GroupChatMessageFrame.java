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
 * @param messageId             메시지 PK
 * @param roomId                소속 방 ID
 * @param senderUserId          발신 사용자. 계정 삭제로 NULL 처리된 메시지는 {@code null}.
 * @param senderNickname        발신자 닉네임(서버 배치 조회). 발신자 NULL 이면 {@code null} — 클라가 "(알 수 없음)" 처리.
 * @param senderProfileImageUrl 발신자 유효 프사 URL(GP-1 FR-6, 서버 배치 조회). 발신자 NULL 또는 프사 없음이면
 *                              {@code null} — 클라가 이니셜 원형 폴백(BR-6). additive 계약(iOS decodeIfPresent 하위호환).
 * @param kind                  메시지 종류(payload 스키마 결정)
 * @param payload               재파싱된 payload 객체(JsonNode). 파싱 불가 시 빈 객체.
 * @param registered            REEL_LINK 만 — 이 릴스의 핀이 그룹에 존재하면 {@code true}. 그 외 kind 는 {@code null}.
 * @param thumbnailUrl          REEL_LINK 만 — 비동기 스크래핑한 og:image 썸네일 URL(GC-3, FR-GC3-2). 스크래핑 전/실패/
 *                              flag off 또는 그 외 kind 는 {@code null}. payload.thumbnailUrl 을 top-level 로 파생한 계약 필드라
 *                              추후 S3 전환 시 파생 로직만 교체하면 iOS 계약은 무변경이다.
 * @param pinSnapshot           PIN_REPLY 만 — payload.pinId 를 배치 조회하여 합성한 핀 카드 스냅샷(registered 파생과 동형,
 *                              자기치유). 그 외 kind 는 {@code null}. additive 계약(iOS decodeIfPresent 하위호환).
 * @param createdAt             ISO8601(offset) 생성 시각 문자열
 */
public record GroupChatMessageFrame(
        Long messageId,
        Long roomId,
        Long senderUserId,
        String senderNickname,
        String senderProfileImageUrl,
        MessageKind kind,
        Object payload,
        Boolean registered,
        String thumbnailUrl,
        PinSnapshot pinSnapshot,
        String createdAt
) {

    /**
     * PIN_REPLY 핀 카드 스냅샷(GroupChatService 가 payload.pinId 배치 조회로 합성).
     *
     * <p>핀 soft-delete 시 {@code deleted=true} + placeName 유지 + 사진/메모 null. 핀 row 자체 미존재
     * (정합 깨짐) 시 {@code deleted=true} + placeName null. 정상 핀은 {@code deleted=false} + 전체 메타.</p>
     *
     * @param pinId               핀 ID(payload 보존값)
     * @param placeName           장소명. 핀 미존재면 {@code null}, 그 외(정상/삭제)는 유지.
     * @param tag                 핀 태그(REEL/WISH/MEMORY). 삭제/미존재면 {@code null}.
     * @param memo                핀 메모. 삭제/미존재면 {@code null}.
     * @param photoThumbnailUrl   핀 사진 썸네일 public URL(키→URL 조합). 삭제/미존재/사진 없음이면 {@code null}.
     * @param photoUrl            핀 사진 원본 public URL(키→URL 조합). 삭제/미존재/사진 없음이면 {@code null}.
     * @param deleted             핀이 soft-delete 됐거나 row 가 사라졌으면 {@code true}.
     */
    public record PinSnapshot(
            Long pinId,
            String placeName,
            String tag,
            String memo,
            String photoThumbnailUrl,
            String photoUrl,
            boolean deleted
    ) { }

    private static final Logger log = LoggerFactory.getLogger(GroupChatMessageFrame.class);

    /**
     * {@link ChatMessage} 엔티티를 그룹 프레임으로 변환한다({@link ChatMessageFrame#from} 동형 + 발신자/registered/
     * thumbnailUrl/pinSnapshot). {@code registered}/{@code thumbnailUrl} 은 REEL_LINK 에서만, {@code pinSnapshot} 은
     * PIN_REPLY 에서만 채워지고 그 외 kind 는 {@code null}이다.
     * {@code senderNickname}/{@code senderProfileImageUrl} 은 서버 배치 조회값으로, 발신자 NULL 이면 둘 다 {@code null}이다(GP-1).
     */
    public static GroupChatMessageFrame from(ChatMessage message, ObjectMapper objectMapper,
                                             String senderNickname, String senderProfileImageUrl,
                                             Boolean registered, String thumbnailUrl, PinSnapshot pinSnapshot) {
        return new GroupChatMessageFrame(
                message.getId(),
                message.getRoomId(),
                message.getSenderUserId(),
                senderNickname,
                senderProfileImageUrl,
                message.getKind(),
                parsePayload(message.getPayloadJson(), objectMapper),
                registered,
                thumbnailUrl,
                pinSnapshot,
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
