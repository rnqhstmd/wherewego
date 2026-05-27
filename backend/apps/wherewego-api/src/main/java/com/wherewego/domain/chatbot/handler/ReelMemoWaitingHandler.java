package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.ReelSavedSelectionSession;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.notification.NotificationService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.RegisterPinResult;
import com.wherewego.domain.pin.want.WantService;
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
 * Phase 12 MEMO_WAITING 상태 핸들러 — 모든 분기(SINGLE_WANT / MULTI_SELECTING / BULK_SAVE)의
 * 핀 저장·메모 적용·WANT 적용·알림 fan-out 을 단일 책임으로 수행한다 (§8.7).
 *
 * <p>처리 흐름:
 * <ol>
 *     <li>세션 snapshot 조회 + state=MEMO_WAITING 검증</li>
 *     <li>메모 결정: 발화 텍스트 또는 null (건너뛰기 / pendingMemo 우선)</li>
 *     <li>{@code groupMemberRepository.countActiveByGroupId} 1회 조회 (N건 루프 진입 전)</li>
 *     <li>선택된 인덱스 1-based 순회:
 *         <ul>
 *             <li>{@code pinService.registerFromSelectionWithDedup} 호출 (좌표/이름 중복 사전 검사)</li>
 *             <li>memo 가 있으면 {@code pin.applyAutoMemo(memo)} 호출 (AUTO 마킹)</li>
 *             <li>{@code wantOnSelected=true} 면 {@code wantService.markWantOnInitialSave(...)} 호출
 *                 — INSERT-only (patch P-5), {@code WantService.toggle} 금지</li>
 *         </ul>
 *     </li>
 *     <li>{@code notificationService.createForChatbotBatch(groupId, userId, pinIds)} 호출</li>
 *     <li>세션 invalidate + COMPLETE 응답</li>
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
    private final GroupMemberRepository groupMemberRepository;
    private final PinService pinService;
    private final PinRepository pinRepository;
    private final WantService wantService;
    private final NotificationService notificationService;

    @Override
    public MessageType supports() {
        return MessageType.REEL_MEMO_WAITING;
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

        // activeMemberCount 1회 조회 — N건 루프 진입 전 재사용 (patch P-5).
        int activeMemberCount = (int) groupMemberRepository.countActiveByGroupId(groupId);

        SaveResult saveResult = saveAllSelected(
                userId, groupId, snapshot, memo, activeMemberCount);

        // 챗봇 일괄 저장 알림 fan-out (저장 성공 핀들만).
        //
        // 알림 정책 (의도적 분리):
        //  - CHATBOT_PINS: 본 핸들러가 발송 — "릴스에서 N곳 저장됨" 일괄 알림 (FR-PIN-12-19).
        //  - WISH_CONVERTED: saveAllSelected 내 wantService.markWantOnInitialSave 가 과반 달성 시
        //    별도 발송 — "위시로 올라간 곳" 단건 알림.
        //  같은 핀에 대해 두 알림이 동시 발송될 수 있으나, 이는 서로 다른 의미(릴스 저장 일괄 vs
        //  과반 달성)를 전달하므로 PRD 정책상 의도된 분리이다. 중복으로 간주하지 않는다.
        if (!saveResult.savedPinIds.isEmpty()) {
            try {
                notificationService.createForChatbotBatch(groupId, userId, saveResult.savedPinIds);
            } catch (RuntimeException e) {
                log.warn("notification (reel memo waiting) failed groupId={} pinCount={}",
                        groupId, saveResult.savedPinIds.size(), e);
            }
        }

        reelSavedSelectionSession.invalidate(botUserKey);
        log.info("MEMO_WAITING completed botUserKey={} saved={} alreadyExisted={} hasMemo={}",
                botUserKey, saveResult.savedPinIds.size(), saveResult.alreadyExistedCount, memo != null);

        return composeResponse(saveResult, memo);
    }

    /**
     * 선택된 인덱스에 대해 핀 저장 + 메모 적용 + WANT 적용을 일괄 수행한다.
     *
     * <p>{@code wantOnSelected} 분기 정책 (PRD AC-12-21, FR-PIN-12-16):
     * <ul>
     *     <li><b>SINGLE_WANT</b>: 사용자가 단건을 명시적으로 [가고 싶어요] 로 선택 →
     *         {@code wantOnSelected=true} (본인 1표 WANT 적용).</li>
     *     <li><b>MULTI_SELECTING</b> (콤마 인덱스 / "전부"): 사용자가 명시적으로 선택한 핀 →
     *         {@code wantOnSelected=true} (선택한 핀에 본인 1표 WANT 적용). PRD AC-12-21
     *         "1,3,5 입력 시 1, 3, 5번 핀은 WANT 1표 포함 저장" 충족.</li>
     *     <li><b>MULTI_SELECTING</b> ("건너뛰기"): 사용자가 명시적으로 선택하지 않음 →
     *         {@code wantOnSelected=false} (전체 REEL 저장만, WANT 미적용).</li>
     *     <li><b>BULK_SAVE</b>: 31개 이상 자동 일괄 저장 → {@code wantOnSelected=false}
     *         (전체 REEL 저장만, WANT 미적용).</li>
     * </ul></p>
     */
    @Transactional
    public SaveResult saveAllSelected(Long userId, Long groupId,
                                       ReelSavedSelectionSession.Snapshot snapshot,
                                       String memo, int activeMemberCount) {
        List<PlaceSearchHit> places = snapshot.places();
        Set<Integer> selected = snapshot.selectedIndices();
        String instagramUrl = snapshot.instagramUrl();
        boolean wantOnSelected = snapshot.wantOnSelected();

        List<Long> savedPinIds = new ArrayList<>();
        List<String> savedNames = new ArrayList<>();
        int alreadyExistedCount = 0;

        for (int i = 0; i < places.size(); i++) {
            int oneBasedIdx = i + 1;
            if (!selected.contains(oneBasedIdx)) {
                continue;
            }
            PlaceSearchHit hit = places.get(i);
            try {
                RegisterPinResult result = pinService.registerFromSelectionWithDedup(
                        userId, groupId, hit, instagramUrl);
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
                savedNames.add(pin.getPlaceName());

                // SINGLE_WANT 분기에서 본인 1표 WANT 적용 (patch P-5: INSERT-only).
                if (wantOnSelected) {
                    try {
                        wantService.markWantOnInitialSave(userId, groupId, pin.getId(), activeMemberCount);
                    } catch (RuntimeException e) {
                        log.warn("markWantOnInitialSave failed pinId={} cause={}",
                                pin.getId(), e.getMessage(), e);
                    }
                }
            } catch (DataIntegrityViolationException e) {
                alreadyExistedCount++;
            } catch (RuntimeException e) {
                log.warn("registerFromSelection (reel memo) failed name={} cause={}",
                        hit.placeName(), e.getMessage(), e);
            }
        }
        return new SaveResult(savedPinIds, savedNames, alreadyExistedCount);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MEMO_MAX_LENGTH) return s;
        return s.substring(0, MEMO_MAX_LENGTH) + "…";
    }

    private static ChatbotV1Dto.SkillResponse composeResponse(SaveResult result, String memo) {
        StringBuilder sb = new StringBuilder();
        int savedCount = result.savedPinIds.size();
        if (savedCount > 0) {
            sb.append("✅ ").append(savedCount).append("곳이 저장되었어요\n");
            for (String name : result.savedNames) {
                sb.append("• ").append(name).append('\n');
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

    /** 저장 결과 집계. */
    public record SaveResult(
            List<Long> savedPinIds,
            List<String> savedNames,
            int alreadyExistedCount
    ) { }
}
