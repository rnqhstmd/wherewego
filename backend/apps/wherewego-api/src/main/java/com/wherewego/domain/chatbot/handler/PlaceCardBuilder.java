package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.PlaceSelectionCandidateStore;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multiple 검색 결과를 카카오 i 오픈빌더 BasicCard + 후보 캐시 저장 형태로 빌드한다.
 *
 * <p>{@link InstagramLinkHandler#handleMultiple}와 비동기 폴백 후처리에서 공통 사용.</p>
 */
public final class PlaceCardBuilder {

    private PlaceCardBuilder() { }

    /**
     * 후보 hits를 {@link PlaceSelectionCandidateStore}에 저장하고 BasicCard 페이로드를 반환한다.
     */
    public static ChatbotV1Dto.SkillResponse buildMultipleCard(
            String botUserKey,
            List<PlaceSearchHit> hits,
            String instagramUrl,
            PlaceSelectionCandidateStore store) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        outputs.add(buildCardOutput(botUserKey, hits, instagramUrl, store, null));
        return ChatbotV1Dto.SkillResponse.cards(outputs);
    }

    /**
     * 카드 1개를 outputs 배열에 들어갈 형태(Map<String,Object>)로 반환.
     * 여러 카드 + simpleText를 한 SkillResponse에 합치는 흐름에서 사용.
     *
     * @param keywordHint null이 아니면 카드 title에 "[키워드] 장소를 선택해 주세요"로 노출.
     */
    public static Map<String, Object> buildCardOutput(
            String botUserKey,
            List<PlaceSearchHit> hits,
            String instagramUrl,
            PlaceSelectionCandidateStore store,
            String keywordHint) {

        List<ChatbotV1Dto.Button> buttons = new ArrayList<>();
        for (PlaceSearchHit hit : hits) {
            store.put(
                    botUserKey,
                    hit.kakaoPlaceId(),
                    new PlaceSelectionCandidateStore.Entry(hit, instagramUrl)
            );
            buttons.add(new ChatbotV1Dto.Button(
                    hit.placeName(),
                    "message",
                    hit.placeName(),
                    Map.of("placeId", hit.kakaoPlaceId())
            ));
        }

        StringBuilder description = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            PlaceSearchHit hit = hits.get(i);
            description.append(i + 1).append(". ").append(hit.placeName());
            if (hit.address() != null && !hit.address().isBlank()) {
                description.append(" — ").append(hit.address());
            }
            description.append('\n');
        }

        String title = (keywordHint != null && !keywordHint.isBlank())
                ? ("\"" + keywordHint + "\" — 장소를 선택해 주세요")
                : "장소를 선택해 주세요";
        ChatbotV1Dto.BasicCard card = new ChatbotV1Dto.BasicCard(
                title,
                description.toString().trim(),
                buttons
        );
        return Map.of("basicCard", card);
    }

    /** outputs 배열에 들어갈 simpleText 1개. */
    public static Map<String, Object> simpleTextOutput(String text) {
        return Map.of("simpleText", Map.of("text", text));
    }
}
