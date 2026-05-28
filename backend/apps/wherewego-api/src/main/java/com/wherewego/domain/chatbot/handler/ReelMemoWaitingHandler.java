package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.ReelSavedSelectionSession;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.pin.RegisterPinResult;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 12/13 MEMO_WAITING 상태 핸들러 — 모든 분기(SINGLE_WANT / MULTI_SELECTING / BULK_SAVE)의
 * 핀 저장·메모 적용·알림 fan-out 을 단일 책임으로 수행한다.
 *
 * <p>처리 흐름 (Phase 13, §2.1):
 * <ol>
 *     <li>세션 snapshot 조회 + state=MEMO_WAITING 검증</li>
 *     <li>메모 결정: 발화 텍스트 또는 null (건너뛰기 / pendingMemo 우선)</li>
 *     <li>추출된 <b>모든</b> 핀 순회 (1-based):
 *         <ul>
 *             <li>{@code wishIndices} 에 포함 → {@code tag=WISH}, 아니면 {@code tag=REEL}</li>
 *             <li>{@code pinService.registerFromSelectionWithDedup(.., tag)} 호출 (좌표/이름 중복 사전 검사)</li>
 *             <li>memo 가 있으면 {@code pin.applyAutoMemo(memo)} 호출 (AUTO 마킹)</li>
 *         </ul>
 *     </li>
 *     <li>{@code notificationService.createForChatbotBatch(groupId, userId, pinIds)} 호출</li>
 *     <li>세션 invalidate + COMPLETE 응답 ("✨ 위시 N곳 / 📍 발견 M곳")</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ReelMemoWaitingHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ReelMemoWaitingHandler.class);

    /** "건너뛰기" QuickReply 정확 매칭. */
    static final String SKIP_TEXT = "건너뛰기";

    /** 카카오 simpleText 1000자 안전 절단 기준 (안내 prefix 여유). */
    private static final int MEMO_MAX_LENGTH = 900;

    private final ReelSavedSelectionSession reelSavedSelectionSession;
    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final PinService pinService;
    private final PinRepository pinRepository;
    private final NotificationService notificationService;

    @Override
    public MessageType supports() {
        return MessageType.REEL_MEMO_WAITING;
    }

    @Override
    @Transactional
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String utterance = request.userRequest().utterance() == null
                ? ""
                : request.userRequest().utterance().trim();

        Optional<ReelSavedSelectionSession.Snapshot> snapshotOpt =
                reelSavedSelectionSession.peek(botUserKey);
        if (snapshotOpt.isEmpty()) {
            log.info("MEMO_WAITING session missing botUserKey={}", botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "처리 시간이 지났어요. 인스타 링크를 다시 보내주세요.");
        }
        ReelSavedSelectionSession.Snapshot snapshot = snapshotOpt.get();
        if (snapshot.state() != ReelSavedSelectionSession.State.MEMO_WAITING) {
            log.warn("MEMO_WAITING handler invoked but state={} botUserKey={}",
                    snapshot.state(), botUserKey);
            return ChatbotV1Dto.SkillResponse.simple(
                    "지금은 다른 단계예요. 메시지를 다시 보내주세요.");
        }

        // userId / groupId 가드 (방어적). ChatbotWebhookService 가 ctx.userId 를 세팅하지만,
        // 본 핸들러가 라우팅 대상이 아닌 경로(예: 새 URL 자동 저장 후 진입)에서도 호출되도록 fallback.
        Long userId = ctx.userId();
        if (userId == null) {
            Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
            if (userIdOpt.isEmpty()) {
                reelSavedSelectionSession.invalidate(botUserKey);
                return ChatbotV1Dto.SkillResponse.simple(
                        "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러주세요.");
            }
            userId = userIdOpt.get();
        }
        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            reelSavedSelectionSession.invalidate(botUserKey);
            return ChatbotV1Dto.SkillResponse.simple("그룹에 먼저 참여해주세요.");
        }
        Long groupId = groupIdOpt.get();

        // 메모 결정: pendingMemo > 발화. "건너뛰기" 정확 매칭 시 메모 없음.
        String memo;
        if (snapshot.pendingMemo() != null && !snapshot.pendingMemo().isBlank()) {
            memo = truncate(snapshot.pendingMemo());
        } else if (SKIP_TEXT.equals(utterance) || utterance.isEmpty()) {
            memo = null;
        } else {
            memo = truncate(utterance);
        }

        SaveResult saveResult = saveAll(userId, groupId, snapshot, memo);

        // 챗봇 일괄 저장 알림 fan-out (저장 성공 핀들만).
        // Phase 13: CHATBOT_PINS 알림 1건에 위시·발견 핀이 함께 링크되며, listRecent 가 연결 핀 tag 를
        // 집계하여 "위시 N곳, 발견 M곳" 분리 표시한다 (§2.3).
        if (!saveResult.savedPinIds.isEmpty()) {
            try {
                notificationService.createForChatbotBatch(groupId, userId, saveResult.savedPinIds);
            } catch (RuntimeException e) {
                log.warn("notification (reel memo waiting) failed groupId={} pinCount={}",
                        groupId, saveResult.savedPinIds.size(), e);
            }
        }

        reelSavedSelectionSession.invalidate(botUserKey);
        log.info("MEMO_WAITING completed botUserKey={} wish={} reel={} alreadyExisted={} hasMemo={}",
                botUserKey, saveResult.wishSavedNames.size(), saveResult.reelSavedNames.size(),
                saveResult.alreadyExistedCount, memo != null);

        return composeResponse(saveResult, memo);
    }

    /**
     * Phase 13: 추출된 <b>모든</b> 핀을 저장한다. {@code wishIndices} 에 포함된 인덱스는 WISH,
     * 나머지는 REEL 로 저장한다 (§2.1). 과반/WANT 일체 없음.
     *
     * <table>
     *     <caption>분기별 저장 결과</caption>
     *     <tr><th>케이스</th><th>wishIndices</th><th>저장 결과</th></tr>
     *     <tr><td>SINGLE [위시로 저장]</td><td>{1}</td><td>1번 WISH</td></tr>
     *     <tr><td>SINGLE [발견으로 저장]</td><td>{}</td><td>1번 REEL</td></tr>
     *     <tr><td>MULTI "1,3"</td><td>{1,3}</td><td>1·3 WISH / 나머지 REEL (전부 저장)</td></tr>
     *     <tr><td>MULTI [전부]</td><td>{1..N}</td><td>전체 WISH</td></tr>
     *     <tr><td>MULTI [건너뛰기]/BULK</td><td>{}</td><td>전체 REEL</td></tr>
     * </table>
     */
    @Transactional
    public SaveResult saveAll(Long userId, Long groupId,
                              ReelSavedSelectionSession.Snapshot snapshot, String memo) {
        List<PlaceSearchHit> places = snapshot.places();
        Set<Integer> wishIndices = snapshot.wishIndices();
        String instagramUrl = snapshot.instagramUrl();

        List<Long> savedPinIds = new ArrayList<>();
        List<String> wishSavedNames = new ArrayList<>();
        List<String> reelSavedNames = new ArrayList<>();
        int alreadyExistedCount = 0;

        for (int i = 0; i < places.size(); i++) {
            int oneBasedIdx = i + 1;
            PlaceSearchHit hit = places.get(i);
            PinTag tag = wishIndices.contains(oneBasedIdx) ? PinTag.WISH : PinTag.REEL;
            try {
                RegisterPinResult result = pinService.registerFromSelectionWithDedup(
                        userId, groupId, hit, instagramUrl, tag);
                Pin pin = result.pin();
                if (result.alreadyExisted()) {
                    alreadyExistedCount++;
                    continue;
                }
                // AUTO 메모 적용 (memo 가 있을 때만).
                if (memo != null) {
                    pin.applyAutoMemo(memo);
                    pinRepository.save(pin);
                }
                savedPinIds.add(pin.getId());
                if (tag == PinTag.WISH) {
                    wishSavedNames.add(pin.getPlaceName());
                } else {
                    reelSavedNames.add(pin.getPlaceName());
                }
            } catch (DataIntegrityViolationException e) {
                alreadyExistedCount++;
            } catch (RuntimeException e) {
                log.warn("registerFromSelection (reel memo) failed name={} cause={}",
                        hit.placeName(), e.getMessage(), e);
            }
        }
        return new SaveResult(savedPinIds, wishSavedNames, reelSavedNames, alreadyExistedCount);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MEMO_MAX_LENGTH) return s;
        return s.substring(0, MEMO_MAX_LENGTH) + "…";
    }

    private static ChatbotV1Dto.SkillResponse composeResponse(SaveResult result, String memo) {
        StringBuilder sb = new StringBuilder();
        int wishCount = result.wishSavedNames.size();
        int reelCount = result.reelSavedNames.size();
        if (wishCount > 0 || reelCount > 0) {
            sb.append("✨ 위시 ").append(wishCount).append("곳 / 📍 발견 ")
                    .append(reelCount).append("곳 저장했어요\n");
            for (String name : result.wishSavedNames) {
                sb.append("✨ ").append(name).append('\n');
            }
            for (String name : result.reelSavedNames) {
                sb.append("📍 ").append(name).append('\n');
            }
        }
        if (result.alreadyExistedCount > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("📌 이미 저장된 장소 ").append(result.alreadyExistedCount).append("곳은 건너뛰었어요\n");
        }
        if (memo != null && !memo.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("📝 메모: ").append(memo).append('\n');
        }
        if (sb.length() == 0) {
            return ChatbotV1Dto.SkillResponse.simple(
                    "저장할 장소가 없었어요. 앱에서 직접 등록해 주세요.");
        }
        return ChatbotV1Dto.SkillResponse.simple(sb.toString().trim());
    }

    /** 저장 결과 집계. Phase 13: 위시/발견 이름 목록을 분리한다. */
    public record SaveResult(
            List<Long> savedPinIds,
            List<String> wishSavedNames,
            List<String> reelSavedNames,
            int alreadyExistedCount
    ) { }
}
