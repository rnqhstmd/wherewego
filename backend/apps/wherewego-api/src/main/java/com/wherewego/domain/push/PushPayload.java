package com.wherewego.domain.push;

/**
 * P2 PR-2: APNs 푸시 트리거별 페이로드(FR-17/18). 트리거 3종에 대응하는 정적 팩토리를 제공한다.
 *
 * <p>{@code type}은 클라이언트 라우팅용 커스텀 프로퍼티이며, {@code roomId}는 채팅 트리거에서만
 * 채워지고 핀 저장 트리거에서는 {@code null}이다. {@link com.wherewego.infrastructure.push.apns.ApnsPushSender}
 * 가 이 값들을 SimpleApnsPushNotification 페이로드로 직렬화한다.</p>
 */
public record PushPayload(String title, String body, String type, Long roomId) {

    /** FR-17①: 파트너가 새 장소를 저장. roomId 없음. */
    public static final String TYPE_PIN_SAVED = "PIN_SAVED";

    /** FR-17②: 파트너가 커플 채팅 메시지를 전송. */
    public static final String TYPE_COUPLE_MESSAGE = "COUPLE_MESSAGE";

    /** FR-17③: 봇 장소 추천 결과 도착. */
    public static final String TYPE_BOT_RESULT = "BOT_RESULT";

    /**
     * FR-17①: 핀 저장 알림. 딥링크 대상이 채팅방이 아니므로 roomId는 {@code null}.
     */
    public static PushPayload pinSaved() {
        return new PushPayload("새 장소 저장", "파트너가 새로운 장소를 저장했어요.", TYPE_PIN_SAVED, null);
    }

    /**
     * FR-17②: 커플 채팅 새 메시지 알림.
     */
    public static PushPayload coupleMessage(Long roomId) {
        return new PushPayload("새 메시지", "파트너가 메시지를 보냈어요.", TYPE_COUPLE_MESSAGE, roomId);
    }

    /**
     * FR-17③: 봇 장소 추천 완료 알림.
     */
    public static PushPayload botResult(Long roomId) {
        return new PushPayload("장소 추천 완료", "요청한 장소 카드가 도착했어요.", TYPE_BOT_RESULT, roomId);
    }
}
