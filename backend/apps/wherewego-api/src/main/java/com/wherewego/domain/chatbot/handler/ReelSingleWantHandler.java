package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
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
import java.util.Set;

/**
 * Phase 12/13 SINGLE_WANT 상태 핸들러.
 *
 * <p>릴스에서 장소 1개가 추출된 경우, 사용자는 "위시로 저장" 또는 "발견으로 저장" 중 선택한다 (Phase 13).
 * 어느 쪽이든 1번 핀은 저장되며, [위시로 저장]은 {@code wishIndices={1}} (WISH), [발견으로 저장]은
 * {@code wishIndices={}} (REEL) 로 {@code MEMO_WAITING} 으로 전이한다. 실제 저장은
 * {@link ReelMemoWaitingHandler} 가 wishIndices 기준으로 태그를 결정한다 (§2.1).</p>
 *
 * <p>QuickReply: [건너뛰기] — 메모 없이 즉시 저장하려는 사용자용.</p>
 */
@Component
@RequiredArgsConstructor
public class ReelSingleWantHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ReelSingleWantHandler.class);

    /** SINGLE_WANT QuickReply 정확 매칭 (Phase 13). {@code MessageClassifier} 와 동일 상수 유지. */
    static final String SINGLE_WANT_YES_TEXT = "위시로 저장";
    static final String SINGLE_WANT_NO_TEXT = "발견으로 저장";

    private final ReelSavedSelectionSession reelSavedSelectionSession;

    @Override
    public MessageType supports() {
        // 분류 우선순위에서 YES/NO 가 분리 enum 으로 라우팅되지만, 본 핸들러는 양쪽을 모두 처리한다.
        // ChatbotWebhookService 가 EnumMap 단일 매핑이므로 YES 를 대표로 등록하고,
        // NO 는 별도 핸들러 인스턴스 없이 동일 클래스가 처리하도록 보조 등록 빈을 둔다.
        return MessageType.SINGLE_WANT_YES;
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
            // TTL 만료 등으로 세션이 사라진 경우 — 안내 후 종료.
            log.info("SINGLE_WANT session missing botUserKey={}", botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "처리 시간이 지났어요. 인스타 링크를 다시 보내주세요.");
        }

        ReelSavedSelectionSession.Snapshot snapshot = snapshotOpt.get();
        if (snapshot.state() != ReelSavedSelectionSession.State.SINGLE_WANT) {
            // 상태 mismatch — 방어 코드. classifier 가 잘못 라우팅한 경우.
            log.warn("SINGLE_WANT handler invoked but state={} botUserKey={}", snapshot.state(), botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "지금은 다른 단계예요. 메시지를 다시 보내주세요.");
        }

        boolean saveAsWish = SINGLE_WANT_YES_TEXT.equals(utterance);
        boolean saveAsReel = SINGLE_WANT_NO_TEXT.equals(utterance);
        if (!saveAsWish && !saveAsReel) {
            // 정확 매칭 외 발화 — 안내 + 세션 유지.
            return ChatbotV1Dto.SkillResponse.simple(
                    "아래 버튼 중 하나를 선택해주세요.",
                    quickReplies());
        }

        // Phase 13: 1번 핀은 항상 저장. [위시로 저장]이면 wishIndices={1}, [발견으로 저장]이면 {}.
        Set<Integer> wishIndices = saveAsWish ? new HashSet<>(List.of(1)) : new HashSet<>();
        ReelSavedSelectionSession.Snapshot next = new ReelSavedSelectionSession.Snapshot(
                ReelSavedSelectionSession.State.MEMO_WAITING,
                snapshot.instagramUrl(),
                snapshot.places(),
                wishIndices,
                ZonedDateTime.now().plusSeconds(180),
                null
        );
        reelSavedSelectionSession.put(botUserKey, next);
        log.info("SINGLE_WANT transitioned to MEMO_WAITING botUserKey={} saveAsWish={}", botUserKey, saveAsWish);

        return ChatbotV1Dto.SkillResponse.simple(
                "메모를 남기시겠어요? (3분 내 응답이 없으면 자동 저장됩니다)",
                memoQuickReplies());
    }

    private static List<ChatbotV1Dto.QuickReply> quickReplies() {
        return List.of(
                ChatbotV1Dto.QuickReply.message("위시로 저장", SINGLE_WANT_YES_TEXT),
                ChatbotV1Dto.QuickReply.message("발견으로 저장", SINGLE_WANT_NO_TEXT)
        );
    }

    private static List<ChatbotV1Dto.QuickReply> memoQuickReplies() {
        return List.of(
                ChatbotV1Dto.QuickReply.message("건너뛰기", "건너뛰기")
        );
    }
}
