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

        ChatbotV1Dto.BasicCard card = new ChatbotV1Dto.BasicCard(
                "장소를 선택해 주세요",
                description.toString().trim(),
                buttons
        );
        List<Map<String, Object>> outputs = new ArrayList<>();
        outputs.add(Map.of("basicCard", card));
        return ChatbotV1Dto.SkillResponse.cards(outputs);
    }
}
