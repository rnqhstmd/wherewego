package com.wherewego.domain.chatbot;

import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Skill 요청 → {@link MessageType} 분류. 우선순위 평가:
 * <ol>
 *     <li>PLACE_SELECTION       : {@code action.params.placeId != null}</li>
 *     <li>LINK_CODE             : {@code action.params.code != null} (i 오픈빌더 slot filling)</li>
 *     <li>INSTAGRAM_LINK        : 인스타 URL 패턴 — 새 링크는 항상 신규로 처리 (이전 pending 덮어씀)</li>
 *     <li>INSTAGRAM_PENDING_MEMO: 인스타 URL 아님 + {@link PendingInstagramSession#peek} 존재 → 메모/저장/취소 분기</li>
 *     <li>TEXT_2SEC_CANDIDATE   : 2초 메모 세션</li>
 *     <li>UNKNOWN               : 그 외</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class MessageClassifier {

    private static final Pattern INSTAGRAM_URL = Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*"
    );

    private final TwoSecondMemoSession twoSecondMemoSession;
    private final PendingInstagramSession pendingInstagramSession;

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
                // 새 인스타 URL은 pending 여부와 무관하게 INSTAGRAM_LINK로 처리
                // (핸들러에서 이전 pending 자동 덮어씀).
                return MessageType.INSTAGRAM_LINK;
            }
        }

        // 인스타 URL이 아니고 pending 있으면 메모/저장/취소 분기 핸들러로
        if (pendingInstagramSession.peek(botUserKey).isPresent()) {
            return MessageType.INSTAGRAM_PENDING_MEMO;
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
