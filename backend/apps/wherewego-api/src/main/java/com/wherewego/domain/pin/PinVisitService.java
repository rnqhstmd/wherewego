package com.wherewego.domain.pin;

import com.wherewego.domain.chat.GroupChatService;
import com.wherewego.domain.chat.MessageKind;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.domain.user.UserRepository.UserProfile;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 방문 체크인·추억 전환 정책 v2 — 단일 방문 선언 API(FR-B2/B3, Q1 확정).
 *
 * <p>혼자(companions 빈) + 그룹 활성 멤버 ≥2 → <b>체크인</b>: 태그 불변, 본인 SELF upsert, PIN_VISIT 카드(무푸시).
 * 동행(companions ≥1) 또는 1인 그룹 혼자(FR-I6) → <b>전환</b>: 본인 SELF + 타인 TAGGED union upsert,
 * WISH/REEL → MEMORY 1회 + PIN_MEMORY 카드(푸시). 이미 MEMORY 면 태그 불변 + visits union + 카드 미적재
 * (alreadyConverted, Q3 확정).</p>
 *
 * <p>동시 제출은 핀 비관 락({@code findActiveByIdAndGroupIdForUpdate})으로 직렬화하여 멱등·union 을 락 안에서
 * 결정한다(race 없음). 카드 적재는 핀 트랜잭션과 동일 트랜잭션이라 실패 시 전체 롤백된다(외부 부수효과 없음).
 * visitedAt 은 서버 now(요청 바디로 받지 않음 — 감지 직후 호출 전제).</p>
 */
@Service
@RequiredArgsConstructor
public class PinVisitService {

    private final PinRepository pinRepository;
    private final PinVisitRepository pinVisitRepository;
    private final GroupMemberService groupMemberService;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final GroupChatService groupChatService;

    /**
     * 방문을 선언한다(체크인 또는 추억 전환). companions 는 본인 제외 동행 user id(빈/생략 = 혼자).
     *
     * @param userId        선언하는 본인 user id
     * @param groupId       대상 그룹 id
     * @param pinId         대상 핀 id(그룹 활성 핀 — 비활성/타그룹이면 PIN_NOT_FOUND 404)
     * @param companionUserIds 본인 제외 동행 명단(서버가 본인 자동 제거). 비멤버 포함 시 PIN_VISIT_COMPANION_INVALID 400.
     * @return {@link DeclareVisitResult} — converted/alreadyConverted/visitors
     * @throws CoreException 비멤버면 GROUP_NOT_MEMBER(403), 핀 없음/비활성이면 PIN_NOT_FOUND(404),
     *                       동행 명단에 비멤버가 있으면 PIN_VISIT_COMPANION_INVALID(400)
     */
    @Transactional
    public DeclareVisitResult declareVisit(Long userId, Long groupId, Long pinId,
                                           List<Long> companionUserIds) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));

        // 본인 자동 제거 + 동행 명단의 그룹 활성 멤버 검증(중복 제거, 순서 보존).
        List<Long> companions = sanitizeCompanions(groupId, userId, companionUserIds);

        long activeMemberCount = groupMemberRepository.countActiveByGroupId(groupId);
        boolean soloGroup = activeMemberCount <= 1;
        boolean transitionFlow = !companions.isEmpty() || soloGroup;

        ZonedDateTime now = ZonedDateTime.now();

        boolean converted = false;
        boolean alreadyConverted = false;
        List<Long> cardParticipants = null;

        if (!transitionFlow) {
            // 체크인(다인 그룹 혼자): 태그 불변, 본인 SELF upsert(TAGGED→SELF 승격, 재방문 visitedAt 갱신).
            upsertVisit(pinId, userId, now, VisitSource.SELF);
            // PIN_VISIT 카드 적재(무푸시 — GroupChatService 가 kind 로 분기).
            groupChatService.appendVisitCard(groupId, userId, MessageKind.PIN_VISIT, pinId, List.of());
        } else {
            // 전환 플로우: 본인 SELF + 타인 TAGGED union upsert.
            upsertVisit(pinId, userId, now, VisitSource.SELF);
            for (Long companionId : companions) {
                upsertVisit(pinId, companionId, now, VisitSource.TAGGED);
            }
            if (pin.getTag() == PinTag.WISH || pin.getTag() == PinTag.REEL) {
                pin.changeTag(PinTag.MEMORY);
                converted = true;
                // PIN_MEMORY 카드 적재(푸시). payload userIds = 그때 참여 명단 스냅샷(본인 + 동행).
                cardParticipants = participantSnapshot(userId, companions);
                groupChatService.appendVisitCard(
                        groupId, userId, MessageKind.PIN_MEMORY, pinId, cardParticipants);
            } else {
                // 이미 MEMORY: 태그 불변, visits union 만 반영, 카드 미적재(Q3 확정).
                alreadyConverted = true;
            }
        }

        return new DeclareVisitResult(converted, alreadyConverted, loadVisitors(pinId));
    }

    /**
     * 본인 제거 + 그룹 활성 멤버 검증. 중복은 제거하되 입력 순서를 보존한다.
     * 비활성/비멤버가 섞이면 PIN_VISIT_COMPANION_INVALID(400)로 전체 거부한다.
     */
    private List<Long> sanitizeCompanions(Long groupId, Long userId, List<Long> companionUserIds) {
        if (companionUserIds == null || companionUserIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long companionId : companionUserIds) {
            if (companionId == null || companionId.equals(userId)) {
                continue; // 본인/누락은 자동 제거(단순화 — 설계 §1-3 ②).
            }
            distinct.add(companionId);
        }
        for (Long companionId : distinct) {
            if (groupMemberRepository.findActiveByGroupIdAndUserId(groupId, companionId).isEmpty()) {
                throw new CoreException(ErrorType.PIN_VISIT_COMPANION_INVALID);
            }
        }
        return new ArrayList<>(distinct);
    }

    /**
     * 방문 upsert — 비관 락(핀) 안이라 select → insert/update 로 충분(ON CONFLICT 불필요).
     * 기존 행은 visitedAt 갱신 + (SELF 요청 시) TAGGED→SELF 승격. TAGGED 요청은 기존 SELF 를 강등하지 않는다.
     */
    private void upsertVisit(Long pinId, Long userId, ZonedDateTime visitedAt, VisitSource source) {
        pinVisitRepository.findByPinIdAndUserId(pinId, userId)
                .ifPresentOrElse(existing -> {
                    existing.touchVisitedAt(visitedAt);
                    if (source == VisitSource.SELF) {
                        existing.promoteToSelf();
                    }
                    pinVisitRepository.save(existing);
                }, () -> pinVisitRepository.save(PinVisit.create(pinId, userId, visitedAt, source)));
    }

    /**
     * 추억 카드 payload 의 참여 user id 명단 스냅샷(본인 우선 + 동행, 중복 제거 순서 보존).
     */
    private List<Long> participantSnapshot(Long userId, List<Long> companions) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        ids.add(userId);
        ids.addAll(companions);
        return new ArrayList<>(ids);
    }

    /**
     * 핀의 현재 방문자 전체를 응답용 {@link PinVisitorResult} 로 합성한다(GP-1 프사 resolver).
     * union 반영 직후 상태를 반환하며, 프로필은 user id 집합 IN 1회 배치 조회한다.
     */
    private List<PinVisitorResult> loadVisitors(Long pinId) {
        List<PinVisit> visits = pinVisitRepository.findByPinIdIn(List.of(pinId));
        if (visits.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        for (PinVisit visit : visits) {
            userIds.add(visit.getUserId());
        }
        Map<Long, UserProfile> profiles = userRepository.findProfilesByIds(userIds);
        List<PinVisitorResult> result = new ArrayList<>(visits.size());
        for (PinVisit visit : visits) {
            UserProfile profile = profiles.get(visit.getUserId());
            result.add(new PinVisitorResult(
                    visit.getUserId(),
                    profile == null ? null : profile.nickname(),
                    profile == null ? null : profile.profileImageUrl(),
                    visit.getSource()));
        }
        return result;
    }
}
