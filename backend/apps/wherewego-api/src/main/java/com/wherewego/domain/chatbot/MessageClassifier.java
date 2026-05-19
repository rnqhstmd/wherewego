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
 *     <li>LINK_CODE       : {@code action.params.code != null} (i 오픈빌더 "그룹 연동" 블록 slot filling)</li>
 *     <li>INSTAGRAM_LINK  : 인스타 게시물/릴 URL 정규식</li>
 *     <li>TEXT_2SEC_CANDIDATE : 위 셋 아니고 {@code twoSecondMemoSession.peek(botUserKey).isPresent()}</li>
 *     <li>UNKNOWN         : 그 외</li>
 * </ol>
 *
 * <p><b>설계 메모:</b> 이전에는 utterance가 {@code ^\d{6}$}이면 LINK_CODE로 분류했으나
 * 일반 6자리 숫자 메시지와 충돌하여 slot filling 흐름으로 분리한다.
 * 카카오 시나리오에서 "그룹 연동" 블록의 슬롯 변수명을 {@code code}로 설정해야 한다.</p>
 */
@Component
@RequiredArgsConstructor
public class MessageClassifier {

    private static final Pattern INSTAGRAM_URL = Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*"
    );

    private final TwoSecondMemoSession twoSecondMemoSession;

    public MessageType classify(ChatbotV1Dto.SkillRequest req, String botUserKey) {
        if (hasParam(req, "placeId")) {
            return MessageType.PLACE_SELECTION;
        }
        if (hasParam(req, "code")) {
            return MessageType.LINK_CODE;
        }

        String utterance = utterance(req);
        if (utterance != null) {
            String trimmed = utterance.trim();
            if (INSTAGRAM_URL.matcher(trimmed).matches()) {
                return MessageType.INSTAGRAM_LINK;
            }
        }

        if (twoSecondMemoSession.peek(botUserKey).isPresent()) {
            return MessageType.TEXT_2SEC_CANDIDATE;
        }
        return MessageType.UNKNOWN;
    }

    private static boolean hasParam(ChatbotV1Dto.SkillRequest req, String key) {
        String value = extractParam(req, key);
        return value != null && !value.isBlank();
    }

    /**
     * 카카오 i 오픈빌더 버튼 {@code action="message"} 전송 시 {@code extra}는
     * 요청의 {@code action.clientExtra}로 들어온다. clientExtra 우선, params 폴백.
     * 핸들러 패키지에서 동일 키 추출이 필요하므로 {@code public}로 노출.
     */
    public static String extractParam(ChatbotV1Dto.SkillRequest req, String key) {
        if (req == null || req.action() == null) {
            return null;
        }
        ChatbotV1Dto.Action action = req.action();
        String value = null;
        if (action.clientExtra() != null) {
            value = action.clientExtra().get(key);
        }
        if ((value == null || value.isBlank()) && action.params() != null) {
            value = action.params().get(key);
        }
        return value;
    }

    /** placeId 추출 - 기존 호출자 호환용 (PlaceSelection 핸들러). */
    public static String extractPlaceId(ChatbotV1Dto.SkillRequest req) {
        return extractParam(req, "placeId");
    }

    private static String utterance(ChatbotV1Dto.SkillRequest req) {
        if (req == null || req.userRequest() == null) {
            return null;
        }
        return req.userRequest().utterance();
    }
}
