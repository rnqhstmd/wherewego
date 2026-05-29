package com.wherewego.domain.chatbot;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Skill 요청 → {@link MessageType} 분류. 우선순위 평가 (Phase 13):
 * <ol>
 *     <li>PLACE_SELECTION       : {@code action.params.placeId != null}</li>
 *     <li>LINK_CODE             : {@code action.params.code != null} (i 오픈빌더 slot filling)</li>
 *     <li>INSTAGRAM_LINK        : 인스타 URL 패턴 — 새 링크는 항상 신규로 처리 (이전 세션 자동 저장 후 덮어씀)</li>
 *     <li>REEL_PLACE_SELECTION  : {@link ReelSavedSelectionSession} state = MULTI_SELECTING 인 경우 모든 발화.
 *         Phase 13에서 1곳~30곳을 모두 이 단계로 통합한다 — 1곳은 "가고 싶어요" / "그냥 저장",
 *         2~30곳은 콤마 숫자 / "전부" / "건너뛰기" 발화를 핸들러가 세부 분기한다.
 *         (31곳+ 는 선택 없이 MEMO_WAITING 직행이라 여기 분기가 없다.)</li>
 *     <li>REEL_MEMO_WAITING     : state = MEMO_WAITING 인 경우 모든 발화</li>
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

    /** 연동 코드 폴백 패턴 — 정확히 6자리 숫자 (카카오 slot 파라미터 code 누락 시 구제용). */
    private static final Pattern LINK_CODE_DIGITS = Pattern.compile("^\\d{6}$");

    private final TwoSecondMemoSession twoSecondMemoSession;
    private final ReelSavedSelectionSession reelSavedSelectionSession;
    private final BotUserMappingService botUserMappingService;

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
                // 새 인스타 URL은 세션 여부와 무관하게 INSTAGRAM_LINK로 처리
                // (핸들러에서 이전 세션 자동 저장 후 덮어씀).
                return MessageType.INSTAGRAM_LINK;
            }
        }

        // Phase 12: ReelSavedSelectionSession 상태 기반 분기
        Optional<ReelSavedSelectionSession.Snapshot> reelSnapshot =
                reelSavedSelectionSession.peek(botUserKey);
        if (reelSnapshot.isPresent()) {
            ReelSavedSelectionSession.State state = reelSnapshot.get().state();
            switch (state) {
                // Phase 13: 1곳~30곳 모두 MULTI_SELECTING. [가고 싶어요]/[그냥 저장](1곳),
                // 콤마 번호/[전부]/[건너뛰기](2~30곳) 발화를 핸들러가 통합 처리한다.
                // (31곳+ 는 선택 단계 없이 MEMO_WAITING 직행이라 여기 분기가 없다.)
                case MULTI_SELECTING -> {
                    return MessageType.REEL_PLACE_SELECTION;
                }
                case MEMO_WAITING -> {
                    return MessageType.REEL_MEMO_WAITING;
                }
                default -> {
                    // IDLE / PROCESSING / COMPLETE 는 사용자 입력으로 분기할 단계가 아니다.
                }
            }
        }

        if (twoSecondMemoSession.peek(botUserKey).isPresent()) {
            return MessageType.TEXT_2SEC_CANDIDATE;
        }

        // 폴백 (연동 안전망): 카카오 slot 파라미터(code)가 누락된 채 사용자가 6자리 코드를 채팅으로
        // 직접 입력한 경우를 구제한다. 여기 도달 = placeId/code 파라미터 없음 + 인스타 URL 아님 +
        // 활성 릴스 세션 없음 + 2초 메모 세션 없음. 추가로 "미연동" 사용자일 때만 LINK_CODE 로 본다
        // (이미 연동된 사용자가 보낸 6자리 숫자는 연동 의도가 아니므로 일반 대화 오인을 막기 위해 제외).
        if (utterance != null
                && LINK_CODE_DIGITS.matcher(utterance.trim()).matches()
                && botUserMappingService.resolveUserId(botUserKey).isEmpty()) {
            return MessageType.LINK_CODE;
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
