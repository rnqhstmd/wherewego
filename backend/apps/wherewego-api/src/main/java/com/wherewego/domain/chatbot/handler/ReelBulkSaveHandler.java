package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.chatbot.ChatbotContext;
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
 * Phase 12 BULK_SAVE 상태 핸들러 (31개+ 추출 시).
 *
 * <p>BULK_SAVE 는 사용자 선택 없이 전체 REEL 로 저장하며, 본 핸들러는 사용자가 입력한 메모(또는
 * "건너뛰기")를 받아 곧장 MEMO_WAITING 으로 전이시킨다 ({@link ReelMemoWaitingHandler}가 실제 저장 수행).</p>
 *
 * <p>BULK_SAVE 단계에서는 {@link ReelMemoWaitingHandler} 와의 책임 분리를 유지하기 위해 본 핸들러가
 * 직접 핀 저장을 하지 않고, 인덱스를 "전부"로 채운 채 MEMO_WAITING 으로 진입시킨다. 결과적으로 SINGLE_WANT
 * 와 동일한 코드 경로(MEMO_WAITING 핸들러) 1개만 핀 저장을 담당하게 된다.</p>
 *
 * <p><b>라우팅</b>: 본 클래스는 {@link MessageHandler} 인터페이스를 구현하지 않는다.
 * classifier 가 MULTI_SELECTING/BULK_SAVE 를 동일 {@code REEL_PLACE_SELECTION} 으로 분류하므로
 * 라우터 진입점은 {@link ReelMultiSelectionHandler} 1개이며, state=BULK_SAVE 일 때 본 핸들러로
 * 위임 호출된다 (EnumMap 단일 매핑 충돌 방지).</p>
 */
@Component
@RequiredArgsConstructor
public class ReelBulkSaveHandler {

    private static final Logger log = LoggerFactory.getLogger(ReelBulkSaveHandler.class);

    static final String SKIP_TEXT = "건너뛰기";

    private final ReelSavedSelectionSession reelSavedSelectionSession;

    /**
     * {@link ReelMultiSelectionHandler} 에서 state=BULK_SAVE 분기 시 위임 호출된다.
     */
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String utterance = request.userRequest().utterance() == null
                ? ""
                : request.userRequest().utterance().trim();

        Optional<ReelSavedSelectionSession.Snapshot> snapshotOpt =
                reelSavedSelectionSession.peek(botUserKey);
        if (snapshotOpt.isEmpty()) {
            log.info("BULK_SAVE session missing botUserKey={}", botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "처리 시간이 지났어요. 인스타 링크를 다시 보내주세요.");
        }

        ReelSavedSelectionSession.Snapshot snapshot = snapshotOpt.get();
        if (snapshot.state() != ReelSavedSelectionSession.State.BULK_SAVE) {
            log.warn("BULK_SAVE handler invoked but state={} botUserKey={}", snapshot.state(), botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "지금은 다른 단계예요. 메시지를 다시 보내주세요.");
        }

        int total = snapshot.places().size();
        // BULK_SAVE 는 전체 인덱스를 항상 선택. 메모만 입력받는다.
        HashSet<Integer> all = new HashSet<>(
                IntStream.rangeClosed(1, total).boxed().collect(Collectors.toList()));

        String pendingMemo = SKIP_TEXT.equals(utterance) ? null : utterance;

        ReelSavedSelectionSession.Snapshot next = new ReelSavedSelectionSession.Snapshot(
                ReelSavedSelectionSession.State.MEMO_WAITING,
                snapshot.instagramUrl(),
                snapshot.places(),
                all,
                false,
                ZonedDateTime.now().plusSeconds(180),
                pendingMemo
        );
        reelSavedSelectionSession.put(botUserKey, next);
        log.info("BULK_SAVE transitioned to MEMO_WAITING botUserKey={} totalPlaces={} hasMemo={}",
                botUserKey, total, pendingMemo != null);

        // BULK_SAVE 의 다음 발화는 본 메서드 호출과 동시에 메모 입력으로 간주되므로,
        // 사용자에게는 곧장 ReelMemoWaitingHandler 가 응답해야 하나, 본 핸들러의 응답은
        // "전체 N개 핀이 저장되었어요" 형태로 통합해 제공한다. 핀 저장은 ReelMemoWaitingHandler
        // 가 단독 수행하도록 두고, 본 핸들러는 안내만 한다.
        return ChatbotV1Dto.SkillResponse.simple(
                "전체 " + total + "개 핀을 저장할게요. "
                        + (pendingMemo != null ? "메모 함께 적용됩니다." : "메모 없이 저장됩니다."),
                List.of(ChatbotV1Dto.QuickReply.message("건너뛰기", SKIP_TEXT))
        );
    }
}
