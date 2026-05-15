package com.wherewego.domain.chatbot;

import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Skill 요청 → {@link MessageType} 분류. 우선순위 평가:
 * <ol>
 *     <li>PLACE_SELECTION : {@code action.params.placeId != null}</li>
 *     <li>LINK_CODE       : utterance trim 후 {@code ^\d{6}$}</li>
 *     <li>INSTAGRAM_LINK  : 인스타 게시물/릴 URL 정규식</li>
 *     <li>TEXT_2SEC_CANDIDATE : 위 셋 아니고 {@code twoSecondMemoSession.peek(botUserKey).isPresent()}</li>
 *     <li>UNKNOWN         : 그 외</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class MessageClassifier {

    private static final Pattern LINK_CODE = Pattern.compile("^\\d{6}$");
    private static final Pattern INSTAGRAM_URL = Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*"
    );

    private final TwoSecondMemoSession twoSecondMemoSession;

    public MessageType classify(ChatbotV1Dto.SkillRequest req, String botUserKey) {
        if (hasPlaceId(req)) {
            return MessageType.PLACE_SELECTION;
        }

        String utterance = utterance(req);
        if (utterance != null) {
            String trimmed = utterance.trim();
            if (LINK_CODE.matcher(trimmed).matches()) {
                return MessageType.LINK_CODE;
            }
            if (INSTAGRAM_URL.matcher(trimmed).matches()) {
                return MessageType.INSTAGRAM_LINK;
            }
        }

        if (twoSecondMemoSession.peek(botUserKey).isPresent()) {
            return MessageType.TEXT_2SEC_CANDIDATE;
        }
        return MessageType.UNKNOWN;
    }

    private static boolean hasPlaceId(ChatbotV1Dto.SkillRequest req) {
        if (req == null || req.action() == null || req.action().params() == null) {
            return false;
        }
        String placeId = req.action().params().get("placeId");
        return placeId != null && !placeId.isBlank();
    }

    private static String utterance(ChatbotV1Dto.SkillRequest req) {
        if (req == null || req.userRequest() == null) {
            return null;
        }
        return req.userRequest().utterance();
    }
}
