package com.wherewego.domain.chat;

import com.wherewego.domain.place.PlaceSearchHit;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 봇 1턴(인스타 링크 → Gemini 장소 추출 → PLACE_CARDS) 결과의 앱용 payload를 생성한다.
 *
 * <p>카카오 i 오픈빌더 BasicCard({@code basicCard}/{@code outputs}/{@code quickReplies}) 형식이 아닌
 * 앱 전용 깔끔한 스키마를 만든다. 출력은 순수 데이터 구조(record)로, 이후 ChatMessageAppender가
 * {@code ObjectMapper}로 직렬화하여 {@code chat_message.payload_json}에 저장하고,
 * STOMP 프레임 payload(객체)로도 그대로 노출한다.</p>
 *
 * <p>입력은 카카오 Local 검색 결과({@link PlaceSearchHit})를 그대로 재사용한다
 * (장소명/주소/좌표 + kakaoPlaceId). 카드 빌더 로직(버튼/후보 캐시/카카오 응답)은 재사용하지 않고
 * 데이터만 앱 스키마로 매핑한다.</p>
 *
 * @see MessageKind#PLACE_CARDS
 */
@Component
public class BotPlaceCardsPayloadBuilder {

    /**
     * 장소 후보 리스트를 앱용 PLACE_CARDS payload로 변환한다.
     *
     * @param hits Gemini 추출 + 카카오 Local 검색으로 확정된 장소 후보. null이면 빈 리스트로 처리.
     * @return 직렬화 대상 payload. {@code hits}가 비어 있으면 빈 {@code cards} 리스트를 가진 payload.
     */
    public PlaceCardsPayload build(List<PlaceSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new PlaceCardsPayload(List.of());
        }
        List<PlaceCard> cards = hits.stream()
                .map(BotPlaceCardsPayloadBuilder::toCard)
                .toList();
        return new PlaceCardsPayload(cards);
    }

    private static PlaceCard toCard(PlaceSearchHit hit) {
        return new PlaceCard(
                hit.kakaoPlaceId(),
                hit.placeName(),
                hit.address(),
                hit.latitude(),
                hit.longitude()
        );
    }

    /**
     * PLACE_CARDS payload 루트. {@code chat_message.payload_json}/STOMP 프레임 payload로 직렬화된다.
     *
     * @param cards 추출된 장소 카드 목록
     */
    public record PlaceCardsPayload(List<PlaceCard> cards) { }

    /**
     * 앱 장소 카드 1건. {@link PlaceSearchHit}의 가용 필드를 앱 스키마로 매핑한다.
     *
     * @param kakaoPlaceId 카카오 Local 장소 식별자
     * @param name         장소명
     * @param address      주소 (없으면 null)
     * @param latitude     위도 (없으면 null)
     * @param longitude    경도 (없으면 null)
     */
    public record PlaceCard(
            String kakaoPlaceId,
            String name,
            String address,
            Double latitude,
            Double longitude
    ) { }
}
