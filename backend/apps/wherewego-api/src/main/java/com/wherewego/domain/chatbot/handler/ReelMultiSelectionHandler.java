package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.ReelCommaParser;
import com.wherewego.domain.chatbot.ReelSavedSelectionSession;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Phase 12 MULTI_SELECTING / BULK_SAVE 상태 핸들러.
 *
 * <p>릴스에서 2~30개 장소가 추출된 경우(MULTI_SELECTING), 사용자는 콤마 인덱스 또는 "전부" /
 * "건너뛰기" QuickReply 로 응답한다. {@link ReelCommaParser} 가 콤마 파싱을 책임지고,
 * 본 핸들러는 분기·전이만 담당한다.</p>
 *
 * <p>BULK_SAVE(31+) 는 별도 {@link ReelBulkSaveHandler} 에서 처리한다. classifier 가 두 상태를
 * 동일 {@link MessageType#REEL_PLACE_SELECTION} 로 분류하지만, 본 핸들러는 MULTI_SELECTING
 * 만 처리하고 BULK_SAVE 는 위임한다.</p>
 *
 * <p>FORMAT/OUT_OF_RANGE/EMPTY 케이스 모두 세션 상태와 TTL 을 그대로 유지하고 안내만 표시한다
 * (NFR-12-5: TTL 미리셋). Caffeine 의 {@code expireAfterWrite} 특성상 put 시 TTL 이 갱신되므로
 * 본 핸들러는 retry 분기에서 {@code put} 을 호출하지 않는다.</p>
 */
@Component
@RequiredArgsConstructor
public class ReelMultiSelectionHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ReelMultiSelectionHandler.class);

    static final String ALL_TEXT = "전부";
    static final String SKIP_TEXT = "건너뛰기";
    /** Phase 13 통합: 1곳 케이스 QuickReply. "가고 싶어요"=전부 위시, "그냥 저장"=전부 발견. */
    static final String SINGLE_WISH_TEXT = "가고 싶어요";
    static final String SINGLE_REEL_TEXT = "그냥 저장";

    private final ReelSavedSelectionSession reelSavedSelectionSession;
    private final ReelCommaParser reelCommaParser;
    private final ReelBulkSaveHandler reelBulkSaveHandler;

    @Override
    public MessageType supports() {
        return MessageType.REEL_PLACE_SELECTION;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String utterance = request.userRequest().utterance() == null
                ? ""
                : request.userRequest().utterance().trim();

        Optional<ReelSavedSelectionSession.Snapshot> snapshotOpt =
                reelSavedSelectionSession.peek(botUserKey);
        if (snapshotOpt.isEmpty()) {
            log.info("REEL_PLACE_SELECTION session missing botUserKey={}", botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "처리 시간이 지났어요. 인스타 링크를 다시 보내주세요.");
        }

        ReelSavedSelectionSession.Snapshot snapshot = snapshotOpt.get();
        // BULK_SAVE 는 별도 핸들러로 위임. 동일 MessageType 으로 라우팅되므로 본 진입점에서 분기.
        if (snapshot.state() == ReelSavedSelectionSession.State.BULK_SAVE) {
            return reelBulkSaveHandler.handle(request, ctx);
        }
        if (snapshot.state() != ReelSavedSelectionSession.State.MULTI_SELECTING) {
            log.warn("MULTI_SELECTING handler invoked but state={} botUserKey={}",
                    snapshot.state(), botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "지금은 다른 단계예요. 메시지를 다시 보내주세요.");
        }

        int total = snapshot.places().size();

        // Phase 13: 전체 핀은 항상 저장된다. wishIndices 에 든 것만 WISH, 나머지는 REEL.
        // "전부" → 전부 위시 → wishIndices={1..N}.
        // "전부"(다중) 또는 "가고 싶어요"(1곳) → 전부 위시 → wishIndices={1..N}.
        if (ALL_TEXT.equals(utterance) || SINGLE_WISH_TEXT.equals(utterance)) {
            HashSet<Integer> all = new HashSet<>(
                    IntStream.rangeClosed(1, total).boxed().collect(Collectors.toList()));
            return transitionToMemoWaiting(botUserKey, snapshot, all,
                    "전체 " + total + "곳을 위시로 저장할게요. ");
        }

        // "건너뛰기"(다중) 또는 "그냥 저장"(1곳) → 위시 없음 → wishIndices={} → 전체 REEL(발견) 저장.
        if (SKIP_TEXT.equals(utterance) || SINGLE_REEL_TEXT.equals(utterance)) {
            return transitionToMemoWaiting(botUserKey, snapshot, new HashSet<>(),
                    "전체 " + total + "곳을 발견으로 저장할게요. ");
        }

        // 콤마 파싱 시도. 선택분 = 위시, 나머지 = 발견 (전부 저장).
        ReelCommaParser.Result result = reelCommaParser.parse(utterance, total);
        switch (result.status()) {
            case OK -> {
                HashSet<Integer> wishIndices = new HashSet<>(result.indices());
                int reelCount = total - wishIndices.size();
                return transitionToMemoWaiting(botUserKey, snapshot, wishIndices,
                        wishIndices.size() + "곳을 위시로 저장할게요. 나머지 " + reelCount + "곳은 발견으로 저장돼요. ");
            }
            case FORMAT_MISMATCH -> {
                return ChatbotV1Dto.SkillResponse.simple(
                        "숫자만 입력해주세요. 예: 1,3,5", multiQuickReplies());
            }
            case OUT_OF_RANGE -> {
                return ChatbotV1Dto.SkillResponse.simple(
                        "1~" + total + " 사이의 번호만 입력해주세요.", multiQuickReplies());
            }
            case EMPTY -> {
                return ChatbotV1Dto.SkillResponse.simple(
                        "번호 또는 [전부]/[건너뛰기]를 선택해주세요.", multiQuickReplies());
            }
            default -> {
                return ChatbotV1Dto.SkillResponse.simple(
                        "입력을 인식하지 못했어요.", multiQuickReplies());
            }
        }
    }

    /**
     * MULTI_SELECTING → MEMO_WAITING 전이 (Phase 13). 전체 핀은 항상 저장되며,
     * {@code wishIndices} 에 든 핀만 WISH, 나머지는 REEL 로 저장된다 (§2.1).
     * "전부" → 전부 위시, "건너뛰기" → 위시 없음, 콤마 → 선택분만 위시.
     */
    private ChatbotV1Dto.SkillResponse transitionToMemoWaiting(String botUserKey,
                                                               ReelSavedSelectionSession.Snapshot prev,
                                                               HashSet<Integer> wishIndices,
                                                               String prefix) {
        ReelSavedSelectionSession.Snapshot next = new ReelSavedSelectionSession.Snapshot(
                ReelSavedSelectionSession.State.MEMO_WAITING,
                prev.instagramUrl(),
                prev.places(),
                wishIndices,
                ZonedDateTime.now().plusSeconds(180),
                null
        );
        reelSavedSelectionSession.put(botUserKey, next);
        log.info("MULTI_SELECTING transitioned to MEMO_WAITING botUserKey={} wishCount={}",
                botUserKey, wishIndices.size());
        return ChatbotV1Dto.SkillResponse.simple(
                prefix + "메모를 남기시겠어요? (3분 내 응답이 없으면 자동 저장됩니다)",
                memoQuickReplies());
    }

    private static List<ChatbotV1Dto.QuickReply> multiQuickReplies() {
        return List.of(
                ChatbotV1Dto.QuickReply.message("전부", ALL_TEXT),
                ChatbotV1Dto.QuickReply.message("건너뛰기", SKIP_TEXT)
        );
    }

    private static List<ChatbotV1Dto.QuickReply> memoQuickReplies() {
        return List.of(
                ChatbotV1Dto.QuickReply.message("건너뛰기", "건너뛰기")
        );
    }
}
